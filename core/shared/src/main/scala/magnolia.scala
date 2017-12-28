package magnolia

import scala.reflect._, macros._
import scala.collection.immutable.ListMap
import language.existentials
import language.higherKinds

/** the object which defines the Magnolia macro */
object Magnolia {
  import CompileTimeState._

  /** derives a generic typeclass instance for the type `T`
    *
    *  This is a macro definition method which should be bound to a method defined inside a Magnolia
    *  generic derivation object, that is, one which defines the methods `combine`, `dispatch` and
    *  the type constructor, `Typeclass[_]`. This will typically look like,
    *  <pre>
    *  object Derivation {
    *    // other definitions
    *    implicit def gen[T]: Typeclass[T] = Magnolia.gen[T]
    *  }
    *  </pre>
    *  which would support automatic derivation of typeclass instances by calling
    *  `Derivation.gen[T]` or with `implicitly[Typeclass[T]]`, if the implicit method is imported
    *  into the current scope.
    *
    *  The definition expects a type constructor called `Typeclass`, taking one *-kinded type
    *  parameter to be defined on the same object as a means of determining how the typeclass should
    *  be genericized. While this may be obvious for typeclasses like `Show[T]` which take only a
    *  single type parameter, Magnolia can also derive typeclass instances for types such as
    *  `Decoder[Format, Type]` which would typically fix the `Format` parameter while varying the
    *  `Type` parameter.
    *
    *  While there is no "interface" for a derivation, in the object-oriented sense, the Magnolia
    *  macro expects to be able to call certain methods on the object within which it is bound to a
    *  method.
    *
    *  Specifically, for deriving case classes (product types), the macro will attempt to call the
    *  `combine` method with an instance of [[CaseClass]], like so,
    *  <pre>
    *    &lt;derivation&gt;.combine(&lt;caseClass&gt;): Typeclass[T]
    *  </pre>
    *  That is to say, the macro expects there to exist a method called `combine` on the derivation
    *  object, which may be called with the code above, and for it to return a type which conforms
    *  to the type `Typeclass[T]`. The implementation of `combine` will therefore typically look
    *  like this,
    *  <pre>
    *    def combine[T](caseClass: CaseClass[Typeclass, T]): Typeclass[T] = ...
    *  </pre>
    *  however, there is the flexibility to provide additional type parameters or additional
    *  implicit parameters to the definition, provided these do not affect its ability to be invoked
    *  as described above.
    *
    *  Likewise, for deriving sealed traits (coproduct or sum types), the macro will attempt to call
    *  the `dispatch` method with an instance of [[SealedTrait]], like so,
    *  <pre>
    *    &lt;derivation&gt;.dispatch(&lt;sealedTrait&gt;): Typeclass[T]
    *  </pre>
    *  so a definition such as,
    *  <pre>
    *    def dispatch[T](sealedTrait: SealedTrait[Typeclass, T]): Typeclass[T] = ...
    *  </pre>
    *  will suffice, however the qualifications regarding additional type parameters and implicit
    *  parameters apply equally to `dispatch` as to `combine`.
    *  */
  def gen[T: c.WeakTypeTag](c: whitebox.Context): c.Tree = {
    import c.universe._
    import internal._

    val magnoliaPkg = q"_root_.magnolia"
    val scalaPkg = q"_root_.scala"

    val repeatedParamClass = definitions.RepeatedParamClass
    val scalaSeqType = typeOf[Seq[_]].typeConstructor

    val prefixType = c.prefix.tree.tpe

    def fail(msg: String): Nothing = c.abort(c.enclosingPosition, s"magnolia: $msg")
    def info(msg: String): Unit = c.info(c.enclosingPosition, s"magnolia: $msg", true)

    def companionRef(tpe: Type): Tree = {
      val global = c.universe match { case global: scala.tools.nsc.Global => global }
      val globalTpe = tpe.asInstanceOf[global.Type]
      val companion = globalTpe.typeSymbol.companionSymbol
      if (companion != NoSymbol)
        global.gen.mkAttributedRef(globalTpe.prefix, companion).asInstanceOf[Tree]
      else q"${tpe.typeSymbol.name.toTermName}"
    }

    def getTypeMember(name: String) = {
      val typeDefs = prefixType.baseClasses.flatMap { cls =>
        cls.asType.toType.decls.filter(_.isType).find(_.name.toString == name).map { tpe =>
          tpe.asType.toType.asSeenFrom(prefixType, cls)
        }
      }

      val typeConstructorOpt = typeDefs.headOption.map(_.typeConstructor)

      typeConstructorOpt getOrElse
        fail("the derivation object does not define the $name type constructor")
    }

    val typeConstructor = getTypeMember("Typeclass")

    def getMethod(termName: String): Option[MethodSymbol] = {
      val term = TermName(termName)
      val cls = c.prefix.tree.tpe.baseClasses.find(_.asType.toType.decl(term) != NoSymbol)
      cls.map { c =>
        c.asType.toType.decl(term).asTerm.asMethod
      }
    }

    // FIXME: Only run these methods if they're used, particularly `dispatch`
    lazy val combineMethod = getMethod("combine")
      .map { m =>
        q"${c.prefix}.combine"
      }
      .getOrElse {
        fail("the method `combine` should be defined, taking a single parameter of type CaseClass[Typeclass, _]")
      }

    lazy val dispatchMethod = getMethod("dispatch")
      .map { m =>
        q"${c.prefix}.dispatch"
      }
      .getOrElse {
        fail(
          s"the method `dispatch` should be defined, taking a single parameter of type SealedTrait[Typeclass, _]"
        )
      }

    def findType(key: Type): Option[TermName] =
      recursionStack(c.enclosingPosition).frames.find(_.genericType == key).map(_.termName(c))

    case class Implicit(typ: c.Type, tree: c.Tree)

    def search[T](path: TypePath, key: Type, value: TermName)(fn: => T): Option[T] = {
      val oldRecursionStack = recursionStack.get(c.enclosingPosition)
      recursionStack = recursionStack.updated(
        c.enclosingPosition,
        oldRecursionStack.map(_.push(path, key, value)).getOrElse {
          Stack(Map(), List(Frame(path, key, value)), Nil)
        }
      )

      try Some(fn)
      catch { case e: Exception => None } finally {
        val currentStack = recursionStack(c.enclosingPosition)
        recursionStack = recursionStack.updated(c.enclosingPosition, currentStack.pop())
      }
    }

    val removeDeferred: Transformer = new Transformer {
      override def transform(tree: Tree): Tree = tree match {
        case q"$magnoliaPkg.Deferred.apply[$returnType](${Literal(Constant(method: String))})" =>
          q"${TermName(method)}"
        case _ =>
          super.transform(tree)
      }
    }

    def typeclassTree(paramName: Option[String],
                      genericType: Type,
                      typeConstructor: Type,
                      assignedName: TermName): Tree = {

      val searchType = appliedType(typeConstructor, genericType)

      val deferredRef = findType(genericType).map { methodName =>
        val methodAsString = methodName.decodedName.toString
        q"$magnoliaPkg.Deferred.apply[$searchType]($methodAsString)"
      }

      val foundImplicit = deferredRef.orElse {
        val (inferredImplicit, newStack) =
          recursionStack(c.enclosingPosition).lookup(c)(searchType) {
            val implicitSearchTry = scala.util.Try {
              val genericTypeName: String =
                genericType.typeSymbol.name.decodedName.toString.toLowerCase

              val assignedName: TermName = TermName(c.freshName(s"${genericTypeName}Typeclass"))

              search(ChainedImplicit(genericType.toString), genericType, assignedName) {
                c.inferImplicitValue(searchType, false, false)
              }.get
            }

            implicitSearchTry.toOption.orElse(
              directInferImplicit(genericType, typeConstructor).map(_.tree)
            )
          }
        recursionStack = recursionStack.updated(c.enclosingPosition, newStack)
        inferredImplicit
      }

      foundImplicit.getOrElse {
        val currentStack: Stack = recursionStack(c.enclosingPosition)

        val error = ImplicitNotFound(genericType.toString,
                                     recursionStack(c.enclosingPosition).frames.map(_.path))

        val updatedStack = currentStack.copy(errors = error :: currentStack.errors)
        recursionStack = recursionStack.updated(c.enclosingPosition, updatedStack)

        val stackPaths = recursionStack(c.enclosingPosition).frames.map(_.path)
        val stack = stackPaths.mkString("    in ", "\n    in ", "\n")

        fail(s"could not find typeclass for type $genericType\n$stack")
      }
    }

    // From Shapeless: https://github.com/milessabin/shapeless/blob/master/core/src/main/scala/shapeless/generic.scala#L698
    // Cut-n-pasted (with most original comments) and slightly adapted from
    // https://github.com/scalamacros/paradise/blob/c14c634923313dd03f4f483be3d7782a9b56de0e/plugin/src/main/scala/org/scalamacros/paradise/typechecker/Namers.scala#L568-L613
    def patchedCompanionSymbolOf(original: c.Symbol): c.Symbol = {
      // see https://github.com/scalamacros/paradise/issues/7
      // also see https://github.com/scalamacros/paradise/issues/64

      val global = c.universe.asInstanceOf[scala.tools.nsc.Global]
      val typer = c
        .asInstanceOf[scala.reflect.macros.runtime.Context]
        .callsiteTyper
        .asInstanceOf[global.analyzer.Typer]
      
      val ctx = typer.context
      val owner = original.owner

      import global.analyzer.Context

      original.companion.orElse {
        import global._
        implicit class PatchedContext(ctx: Context) {

          trait PatchedLookupResult { def suchThat(criterion: Symbol => Boolean): Symbol }

          def patchedLookup(name: Name, expectedOwner: Symbol) = new PatchedLookupResult {
            def suchThat(criterion: Symbol => Boolean): Symbol = {
              var res: Symbol = NoSymbol
              var ctx = PatchedContext.this.ctx
              while (res == NoSymbol && ctx.outer != ctx) {
                // NOTE: original implementation says `val s = ctx.scope lookup name`
                // but we can't use it, because Scope.lookup returns wrong results when the lookup is ambiguous
                // and that triggers https://github.com/scalamacros/paradise/issues/64
                val s = {
                  val lookupResult = ctx.scope.lookupAll(name).filter(criterion).toList
                  lookupResult match {
                    case Nil          => NoSymbol
                    case List(unique) => unique
                    case _ =>
                      fail(
                        s"unexpected multiple results for a companion symbol lookup for $original#{$original.id}"
                      )
                  }
                }
                if (s != NoSymbol && s.owner == expectedOwner)
                  res = s
                else
                  ctx = ctx.outer
              }
              res
            }
          }
        }

        ctx
          .patchedLookup(original.asInstanceOf[global.Symbol].name.companionName,
                         owner.asInstanceOf[global.Symbol])
          .suchThat(
            sym =>
              (original.isTerm || sym.hasModuleFlag) &&
                (sym isCoDefinedWith original.asInstanceOf[global.Symbol])
          )
          .asInstanceOf[c.universe.Symbol]
      }
    }

    def directInferImplicit(genericType: c.Type, typeConstructor: Type): Option[Implicit] = {

      lazy val genericTypeName: String =
        genericType.typeSymbol.name.decodedName.toString.toLowerCase

      lazy val assignedName: TermName = TermName(c.freshName(s"${genericTypeName}Typeclass"))
      lazy val typeSymbol = genericType.typeSymbol
      lazy val classType = if (typeSymbol.isClass) Some(typeSymbol.asClass) else None
      lazy val isCaseClass = classType.exists(_.isCaseClass)
      lazy val isCaseObject = classType.exists(_.isModuleClass)
      lazy val isSealedTrait = classType.exists(_.isSealed)

      lazy val primitives = Set(typeOf[Double],
                                typeOf[Float],
                                typeOf[Short],
                                typeOf[Byte],
                                typeOf[Int],
                                typeOf[Long],
                                typeOf[Char],
                                typeOf[Boolean],
                                typeOf[Unit])

      lazy val isValueClass = genericType <:< typeOf[AnyVal] && !primitives.exists(
        _ =:= genericType
      )

      lazy val resultType = appliedType(typeConstructor, genericType)

      lazy val liftedParamType: Tree = getMethod("param")
        .map { sym =>
          tq"${c.prefix}.ParamType[$genericType, _]"
        }
        .getOrElse(tq"$magnoliaPkg.Param[$typeConstructor, $genericType]")

      lazy val liftedSubtypeType: Tree = getMethod("subtype")
        .map { sym =>
          tq"${c.prefix}.SubtypeType[$genericType, _]"
        }
        .getOrElse(tq"$magnoliaPkg.Subtype[$typeConstructor, $genericType]")

      lazy val caseClassMethod = getMethod("caseClass")
        .map { sym =>
          q"${c.prefix}.caseClass[$genericType, $liftedParamType]"
        }
        .getOrElse(
          q"$magnoliaPkg.Magnolia.caseClass[$typeConstructor, $genericType, $liftedParamType]"
        )

      lazy val caseClassParamNames = getMethod("caseClass")
        .map { sym =>
          sym.paramLists.head.map(_.name.decodedName.toString)
        }
        .getOrElse(List("name", "isCaseObject", "isValueClass", "parameters", "constructor"))

      lazy val sealedTraitMethod = getMethod("sealedTrait")
        .map { sym =>
          q"${c.prefix}.sealedTrait"
        }
        .getOrElse(
          q"$magnoliaPkg.Magnolia.sealedTrait[$typeConstructor, $genericType, $liftedParamType]"
        )

      lazy val sealedTraitParamNames = getMethod("sealedTrait")
        .map { sym =>
          sym.paramLists.head.map(_.name.decodedName.toString)
        }
        .getOrElse(List("name", "subtypes"))

      val className =
        s"${genericType.typeSymbol.owner.fullName}.${genericType.typeSymbol.name.decodedName}"

      val result = if (isCaseObject) {
        val obj = companionRef(genericType)

        val parameters = caseClassParamNames.map {
          case "name"         => q"$className"
          case "isCaseObject" => q"true"
          case "isValueClass" => q"false"
          case "parameters"   => q"new $scalaPkg.Array(0)"
          case "constructor"  => q"(_ => $obj)"
        }

        val impl = q"$combineMethod($caseClassMethod(..$parameters))"

        Some(Implicit(genericType, impl))
      } else if (isCaseClass || isValueClass) {
        val caseClassParameters = genericType.decls.collect {
          case m: MethodSymbol if m.isCaseAccessor || (isValueClass && m.isParamAccessor) =>
            m.asMethod
        }

        case class CaseParam(sym: c.universe.MethodSymbol,
                             repeated: Boolean,
                             typeclass: c.Tree,
                             paramType: c.Type,
                             ref: c.TermName)

        val caseParamsReversed = caseClassParameters.foldLeft[List[CaseParam]](Nil) {
          (acc, param) =>
            val paramName = param.name.decodedName.toString
            val paramTypeSubstituted = param.typeSignatureIn(genericType).resultType

            val (repeated, paramType) = paramTypeSubstituted match {
              case TypeRef(_, `repeatedParamClass`, typeArgs) =>
                true -> appliedType(scalaSeqType, typeArgs)
              case tpe =>
                false -> tpe
            }

            val predefinedRef = acc.find(_.paramType == paramType)

            val caseParamOpt = predefinedRef.map { backRef =>
              CaseParam(param, repeated, q"()", paramType, backRef.ref) :: acc
            }

            caseParamOpt.getOrElse {
              val derivedImplicit =
                search(ProductType(paramName, genericType.toString), genericType, assignedName) {
                  typeclassTree(Some(paramName), paramType, typeConstructor, assignedName)
                }.getOrElse(
                  fail(s"failed to get implicit for type $genericType")
                )

              val ref = TermName(c.freshName("paramTypeclass"))
              val assigned = q"""val $ref = $derivedImplicit"""
              CaseParam(param, repeated, assigned, paramType, ref) :: acc
            }
        }

        val caseParams = caseParamsReversed.reverse

        val paramsVal: TermName = TermName(c.freshName("parameters"))
        val fnVal: TermName = TermName(c.freshName("fn"))

        val preAssignments = caseParams.map(_.typeclass)

        val defaults = if (!isValueClass) {
          val caseClassCompanion = patchedCompanionSymbolOf(genericType.typeSymbol).asModule.info

          // If a companion object is defined with alternative apply methods
          // it is needed get all the alternatives
          val constructorMethods =
            caseClassCompanion.decl(TermName("apply")).alternatives.map(_.asMethod)

          // The last apply method in the alternatives is the one that belongs
          // to the case class, not the user defined companion object
          val indexedConstructorParams =
            constructorMethods.last.paramLists.head.map(_.asTerm).zipWithIndex

          indexedConstructorParams.map {
            case (p, idx) =>
              if (p.isParamWithDefault) {
                val method = TermName("apply$default$" + (idx + 1))
                q"$scalaPkg.Some(${genericType.typeSymbol.companion.asTerm}.$method)"
              } else q"$scalaPkg.None"
          }
        } else List(q"$scalaPkg.None")

        val assignments = caseParams.zip(defaults).zipWithIndex.map {
          case ((CaseParam(param, repeated, typeclass, paramType, ref), defaultVal), idx) =>
            val paramMethod: Tree = getMethod("param")
              .map { sym =>
                q"${c.prefix}.param"
              }
              .getOrElse(q"$magnoliaPkg.Magnolia.param[$typeConstructor, $genericType, $paramType]")

            val paramNames = getMethod("param")
              .map { sym =>
                sym.paramLists.head.map(_.name.decodedName.toString)
              }
              .getOrElse(List("name", "repeated", "typeclass", "default", "dereference"))

            val parameters: List[Tree] = paramNames.map {
              case "name"        => q"${param.name.decodedName.toString}"
              case "repeated"    => q"$repeated"
              case "typeclass"   => q"$ref"
              case "default"     => q"$defaultVal"
              case "dereference" => q"_.${param.name}"
              case other =>
                fail(
                  s"method 'param' has an unexpected parameter with name '$other'; permitted parameter names: default, dereference, name, repeated, typeclass"
                )
            }
            q"""$paramsVal($idx) = $paramMethod(..$parameters)"""
        }

        val parameters = caseClassParamNames.map {
          case "name"         => q"$className"
          case "isCaseObject" => q"false"
          case "isValueClass" => q"$isValueClass"
          case "parameters"   => q"$paramsVal"
          case "constructor" =>
            q"""
            ($fnVal: $liftedParamType => Any) =>
              new $genericType(..${caseParams.zipWithIndex.map {
              case (typeclass, idx) =>
                val arg = q"$fnVal($paramsVal($idx)).asInstanceOf[${typeclass.paramType}]"
                if (typeclass.repeated) q"$arg: _*" else arg
            }})"""
          case other =>
            fail(
              s"method 'caseClass' has an unexpected parameter with name '$other'; permitted parameter names: name, isCaseClass, isValueClass, parameters, constructor"
            )
        }

        val impl = q"$combineMethod($caseClassMethod(..$parameters))"
        Some(
          Implicit(
            genericType,
            q"""{
            ..$preAssignments
            val $paramsVal: $scalaPkg.Array[$liftedParamType] =
              new $scalaPkg.Array(${assignments.length})
            ..$assignments
            
            $combineMethod($caseClassMethod(..$parameters))
          }"""
          )
        )
      } else if (isSealedTrait) {
        val genericSubtypes = classType.get.knownDirectSubclasses.to[List]
        val subtypes = genericSubtypes.map { sub =>
          val subType = sub.asType.toType // FIXME: Broken for path dependent types
          val typeParams = sub.asType.typeParams
          val typeArgs = thisType(sub).baseType(genericType.typeSymbol).typeArgs
          val mapping = (typeArgs.map(_.typeSymbol), genericType.typeArgs).zipped.toMap
          val newTypeArgs = typeParams.map(mapping.withDefault(_.asType.toType))
          val applied = appliedType(subType.typeConstructor, newTypeArgs)
          existentialAbstraction(typeParams, applied)
        }

        if (subtypes.isEmpty) {
          info(s"could not find any direct subtypes of $typeSymbol")
          fail("")
        }

        val subtypesVal: TermName = TermName(c.freshName("subtypes"))

        val typeclasses = subtypes.map { searchType =>
          search(CoproductType(genericType.toString), genericType, assignedName) {
            (searchType, typeclassTree(None, searchType, typeConstructor, assignedName))
          }.getOrElse {
            fail(s"failed to get implicit for type $searchType")
          }
        }

        val assignments = typeclasses.zipWithIndex.map {
          case ((subtype, typeclass), idx) =>
            val subtypeMethod: Tree = getMethod("subtype")
              .map { sym =>
                q"${c.prefix}.subtype"
              }
              .getOrElse(q"$magnoliaPkg.Magnolia.subtype[$typeConstructor, $genericType, $subtype]")

            val subtypeParamNames = getMethod("subtype")
              .map { sym =>
                sym.paramLists.head.map(_.name.decodedName.toString)
              }
              .getOrElse(List("name", "typeclass", "isType", "asType"))

            val parameters = subtypeParamNames.map {
              case "name"      => q"${subtype.typeSymbol.fullName.toString}"
              case "typeclass" => q"$typeclass"
              case "isType"    => q"(t: $genericType) => t.isInstanceOf[$subtype]"
              case "asType"    => q"(t: $genericType) => t.asInstanceOf[$subtype]"
              case other =>
                fail(
                  s"method 'subtype' has an unexpected parameter with name '$other'; permitted parameter names: name, typeclass, isType, asType"
                )
            }

            q"""$subtypesVal($idx) = $subtypeMethod(..$parameters)"""
        }

        val parameters = sealedTraitParamNames.map {
          case "name" =>
            q"""${s"${genericType.typeSymbol.owner.fullName}.${genericType.typeSymbol.name.decodedName}"}"""
          case "subtypes" => q"$subtypesVal: $scalaPkg.Array[$liftedSubtypeType]"
          case other =>
            fail(
              s"method 'sealedTrait' has an unexpected parameter with name '$other'; permitted parameter names: name, subtypes"
            )
        }

        Some {
          Implicit(
            genericType,
            q"""{
            val $subtypesVal: $scalaPkg.Array[$liftedSubtypeType] =
              new $scalaPkg.Array(${assignments.size})
            
            ..$assignments
            
            $dispatchMethod($sealedTraitMethod(..$parameters)): $resultType
          }"""
          )
        }
      } else None

      result.map {
        case Implicit(t, r) =>
          Implicit(t, q"""{
          lazy val $assignedName: $resultType = $r
          $assignedName
        }""")
      }
    }

    val genericType: Type = weakTypeOf[T]

    val currentStack: Stack =
      recursionStack.getOrElse(c.enclosingPosition, Stack(Map(), List(), List()))

    val directlyReentrant = currentStack.frames.headOption.exists(_.genericType == genericType)

    if (directlyReentrant) throw DirectlyReentrantException()

    currentStack.errors.foreach { error =>
      if (!emittedErrors.contains(error)) {
        emittedErrors += error
        val trace = error.path.mkString("\n    in ", "\n    in ", "\n \n")

        val msg = s"could not derive $typeConstructor instance for type ${error.genericType}"

        info(msg + trace)
      }
    }

    val result: Option[Tree] = if (currentStack.frames.nonEmpty) {
      findType(genericType) match {
        case None =>
          directInferImplicit(genericType, typeConstructor).map(_.tree)
        case Some(enclosingRef) =>
          val methodAsString = enclosingRef.toString
          val searchType = appliedType(typeConstructor, genericType)
          Some(q"$magnoliaPkg.Deferred[$searchType]($methodAsString)")
      }
    } else directInferImplicit(genericType, typeConstructor).map(_.tree)

    if (currentStack.frames.isEmpty) recursionStack = ListMap()

    val dereferencedResult = result.map { tree =>
      if (currentStack.frames.isEmpty) c.untypecheck(removeDeferred.transform(tree)) else tree
    }

    dereferencedResult.getOrElse {
      fail(s"could not infer typeclass for type $genericType")
    }
  }

  /** constructs a new [[Subtype]] instance
    *
    *  This method is intended to be called only from code generated by the Magnolia macro, and
    *  should not be called directly from users' code. */
  def subtype[Tc[_], T, S <: T](name: String,
                                typeclass: => Tc[S],
                                isType: T => Boolean,
                                asType: T => S) = {
    lazy val typeclassVal = typeclass
    new Subtype[Tc, T] {
      type SType = S
      def label: String = name
      def typeclass: Tc[SType] = typeclassVal
      def cast: PartialFunction[T, SType] = new PartialFunction[T, S] {
        def isDefinedAt(t: T) = isType(t)
        def apply(t: T): SType = asType(t)
      }
    }
  }

  /** constructs a new [[Param]] instance
    *
    *  This method is intended to be called only from code generated by the Magnolia macro, and
    *  should not be called directly from users' code. */
  def param[Tc[_], T, P](name: String,
                         repeated: Boolean,
                         typeclass: Tc[P],
                         default: => Option[P],
                         dereference: T => P) = {
    val typeclassVal = typeclass
    val defaultVal = default
    val dereferenceVal = dereference
    val repeatedVal = repeated

    new Param[Tc, T] {
      type PType = P
      def label: String = name
      def repeated: Boolean = repeatedVal
      def default: Option[PType] = defaultVal
      def typeclass: Tc[PType] = typeclassVal
      def dereference(t: T): PType = dereferenceVal(t)
    }
  }

  /** constructs a new [[CaseClass]] instance
    *
    *  This method is intended to be called only from code generated by the Magnolia macro, and
    *  should not be called directly from users' code. */
  def caseClass[Tc[_], T, PType](name: String,
                                 isCaseObject: Boolean,
                                 isValueClass: Boolean,
                                 parameters: Array[PType],
                                 constructor: (PType => Any) => T): CaseClass[Tc, T, PType] =
    new CaseClass[Tc, T, PType](name, isCaseObject, isValueClass, parameters) {
      def construct[R](param: PType => R): T = constructor(param)
    }

  /** constructs a new [[CaseClass]] instance
    *
    *  This method is intended to be called only from code generated by the Magnolia macro, and
    *  should not be called directly from users' code. */
  def sealedTrait[Tc[_], T, SubType](name: String,
                                     subtypes: Array[Subtype[Tc, T]]): SealedTrait[Tc, T] =
    new SealedTrait[Tc, T](name, subtypes)
}

private[magnolia] case class DirectlyReentrantException()
    extends Exception("attempt to recurse directly")

private[magnolia] object Deferred { def apply[T](method: String): T = ??? }

private[magnolia] object CompileTimeState {

  sealed class TypePath(path: String) { override def toString = path }
  case class CoproductType(typeName: String) extends TypePath(s"coproduct type $typeName")

  case class ProductType(paramName: String, typeName: String)
      extends TypePath(s"parameter '$paramName' of product type $typeName")

  case class ChainedImplicit(typeName: String)
      extends TypePath(s"chained implicit of type $typeName")

  case class ImplicitNotFound(genericType: String, path: List[TypePath])

  case class Stack(cache: Map[whitebox.Context#Type, Option[whitebox.Context#Tree]],
                   frames: List[Frame],
                   errors: List[ImplicitNotFound]) {

    def lookup(c: whitebox.Context)(t: c.Type)(orElse: => Option[c.Tree]): (Option[c.Tree], Stack) =
      if (cache.contains(t)) {
        (cache(t).asInstanceOf[Option[c.Tree]], this)
      } else {
        val value = orElse
        (value, copy(cache.updated(t, value)))
      }

    def push(path: TypePath, key: whitebox.Context#Type, value: whitebox.Context#TermName): Stack =
      Stack(cache, Frame(path, key, value) :: frames, errors)

    def pop(): Stack = Stack(cache, frames.tail, errors)
  }

  case class Frame(path: TypePath,
                   genericType: whitebox.Context#Type,
                   term: whitebox.Context#TermName) {
    def termName(c: whitebox.Context): c.TermName = term.asInstanceOf[c.TermName]
  }

  var recursionStack: ListMap[api.Position, Stack] = ListMap()
  var emittedErrors: Set[ImplicitNotFound] = Set()
}
