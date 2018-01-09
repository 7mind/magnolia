package magnolia.examples

import magnolia._
import scala.language.experimental.macros

/** typeclass for providing a default value for a particular type */
trait Default[T] { def default: T }

/** companion object and derivation object for [[Default]] */
object Default {

  type Typeclass[T] = Default[T]

  /** constructs a default for each parameter, using the constructor default (if provided),
    *  otherwise using a typeclass-provided default */
  def combine[T](ctx: CaseClass[Default, T]): Default[T] = new Default[T] {
    def default = ctx.construct { param =>
      param.default.getOrElse(param.typeclass.default)
    }
  }

  /** chooses which subtype to delegate to */
  def dispatch[T](ctx: SealedTrait[Default, T])(): Default[T] = new Default[T] {
    def default: T = ctx.subtypes.head.typeclass.default
  }

  /** default value for a string; the empty string */
  implicit val string: Default[String] = new Default[String] { def default = "" }

  /** default value for ints; 0 */
  implicit val int: Default[Int] = new Default[Int] { def default = 0 }

  /** default value for sequences; the empty sequence */
  implicit def seq[A]: Default[Seq[A]] = new Typeclass[Seq[A]] { def default = Seq.empty }

  /** generates default instances of [[Default]] for case classes and sealed traits */
  implicit def gen[T]: Default[T] = macro Magnolia.gen[T]
}

object DefaultNoCoproduct {

  type Typeclass[T] = Default[T]

  /** constructs a default for each parameter, using the constructor default (if provided),
    *  otherwise using a typeclass-provided default */
  def combine[T](ctx: CaseClass[Default, T]): Default[T] = new Default[T] {
    def default = ctx.construct { param =>
      param.default.getOrElse(param.typeclass.default)
    }
  }
  
  /** generates default instances of [[Default]] for case classes and sealed traits */
  implicit def gen[T]: Default[T] = macro Magnolia.gen[T]
}
