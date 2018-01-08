package magnolia.tests

import language.experimental.macros
import estrapade.{test, TestApp}
import contextual.data.scalac._
import contextual.data.fqt._
import contextual.data.txt._
import magnolia.examples._

import scala.util.control.NonFatal

sealed trait Tree[+T]
case class Leaf[+L](value: L) extends Tree[L]
case class Branch[+B](left: Tree[B], right: Tree[B]) extends Tree[B]

sealed trait Path[+A]
case class Destination[+A](value: A) extends Path[A]
case class Crossroad[+A](left: Path[A], right: Path[A]) extends Path[A]
case class OffRoad[+A](path: Option[Path[A]]) extends Path[A]

sealed trait Entity

case class Company(name: String) extends Entity
case class Person(name: String, age: Int) extends Entity
case class Address(line1: String, occupant: Person)

class Length(val value: Int) extends AnyVal

case class FruitBasket(fruits: Fruit*)
case class Lunchbox(fruit: Fruit, drink: String)
object Fruit {
  implicit val showFruit: Show[String, Fruit] =
    new Show[String, Fruit] { def show(f: Fruit): String = f.name }
}
case class Fruit(name: String)

case class Item(name: String, quantity: Int = 1, price: Int)

sealed trait Color
case object Red extends Color
case object Green extends Color
case object Blue extends Color

case class `%%`(`/`: Int, `#`: String)

case class Param(a: String, b: String)
case class Test(param: Param)
object Test {
  def apply(): Test = Test(Param("", ""))

  def apply(a: String)(implicit b: Int): Test = Test(Param(a, b.toString))

  def apply(a: String, b: String): Test = Test(Param(a, b))
}

sealed trait Politician[+S]
case class Accountable[+S](slogan: S) extends Politician[S]
case class Corrupt[+S, +L <: Seq[Company]](slogan: S, lobby: L) extends Politician[S]

sealed trait Box[+A]
case class SimpleBox[+A](value: A) extends Box[A]
case class LabelledBox[+A, L <: String](value: A, var label: L) extends Box[A]

case class Account(id: String, emails: String*)

case class Portfolio(companies: Company*)

case class Recursive(children: Seq[Recursive])

object Tests extends TestApp {

  def tests(): Unit = for (_ <- 1 to 1) {
    test("construct a Show product instance with alternative apply functions") {
      Show.gen[Test].show(Test("a", "b"))
    }.assert(_ == """Test(param=Param(a=a,b=b))""")

    test("construct a Show product instance") {
      Show.gen[Person].show(Person("John Smith", 34))
    }.assert(_ == """Person(name=John Smith,age=34)""")

    test("construct a Show coproduct instance") {
      Show.gen[Person].show(Person("John Smith", 34))
    }.assert(_ == "Person(name=John Smith,age=34)")

    test("serialize a Branch") {
      implicitly[Show[String, Branch[String]]].show(Branch(Leaf("LHS"), Leaf("RHS")))
    }.assert(_ == "Branch(left=Leaf(value=LHS),right=Leaf(value=RHS))")

    test("local implicit beats Magnolia") {
      implicit val showPerson: Show[String, Person] = new Show[String, Person] {
        def show(p: Person) = "nobody"
      }
      implicitly[Show[String, Address]].show(Address("Home", Person("John Smith", 44)))
    }.assert(_ == "Address(line1=Home,occupant=nobody)")

    test("even low-priority implicit beats Magnolia for nested case") {
      import Show.gen
      implicitly[Show[String, Lunchbox]].show(Lunchbox(Fruit("apple"), "lemonade"))
    }.assert(_ == "Lunchbox(fruit=apple,drink=lemonade)")

    test("low-priority implicit does not beat Magnolia when not nested") {
      import Show.gen
      implicitly[Show[String, Fruit]].show(Fruit("apple"))
    }.assert(_ == "Fruit(name=apple)")

    test("low-priority implicit does not beat Magnolia when chained") {
      import Show.gen
      implicitly[Show[String, FruitBasket]].show(FruitBasket(Fruit("apple"), Fruit("banana")))
    }.assert(_ == "FruitBasket(fruits=[Fruit(name=apple),Fruit(name=banana)])")

    test("typeclass implicit scope has lower priority than ADT implicit scope") {
      implicitly[Show[String, Fruit]].show(Fruit("apple"))
    }.assert(_ == "apple")

    test("test equality false") {
      Eq.gen[Entity].equal(Person("John Smith", 34), Person("", 0))
    }.assert(_ == false)

    test("test equality true") {
      Eq.gen[Entity].equal(Person("John Smith", 34), Person("John Smith", 34))
    }.assert(_ == true)

    test("test branch equality true") {
      Eq.gen[Tree[String]].equal(Branch(Leaf("one"), Leaf("two")), Branch(Leaf("one"), Leaf("two")))
    }.assert(_ == true)

    test("construct a default value") {
      Default.gen[Entity].default
    }.assert(_ == Company(""))

    test("construction of Show instance for Leaf") {
      scalac"""
        implicitly[Show[String, Leaf[java.lang.String]]]
      """
    }.assert(_ == Returns(fqt"magnolia.examples.Show[String,magnolia.tests.Leaf[String]]"))

    test("construction of Show instance for Tree") {
      scalac"""
        implicitly[Show[String, Tree[String]]]
      """
    }.assert(_ == Returns(fqt"magnolia.examples.Show[String,magnolia.tests.Tree[String]]"))

    test("serialize a Leaf") {
      implicitly[Show[String, Leaf[String]]].show(Leaf("testing"))
    }.assert(_ == "Leaf(value=testing)")

    test("serialize a Branch as a Tree") {
      implicitly[Show[String, Tree[String]]].show(Branch(Leaf("LHS"), Leaf("RHS")))
    }.assert(_ == "Branch(left=Leaf(value=LHS),right=Leaf(value=RHS))")

    test("serialize case object") {
      implicitly[Show[String, Red.type]].show(Red)
    }.assert(_ == "Red()")

    test("access default constructor values") {
      implicitly[Default[Item]].default
    }.assert(_ == Item("", 1, 0))

    test("serialize case object as a sealed trait") {
      implicitly[Show[String, Color]].show(Blue)
    }.assert(_ == "Blue()")

    test("decode a company") {
      Decoder.gen[Company].decode("""Company(name=Acme Inc)""")
    }.assert(_ == Company("Acme Inc"))

    test("decode a Person as an Entity") {
      implicitly[Decoder[Entity]].decode("""magnolia.tests.Person(name=John Smith,age=32)""")
    }.assert(_ == Person("John Smith", 32))

    test("decode a nested product") {
      implicitly[Decoder[Address]].decode(
        """Address(line1=53 High Street,occupant=Person(name=Richard Jones,age=44))"""
      )
    }.assert(_ == Address("53 High Street", Person("Richard Jones", 44)))

    test("show error stack") {
      scalac"""
        case class Alpha(integer: Double)
        case class Beta(alpha: Alpha)
        Show.gen[Beta]
      """
    }.assert(_ == TypecheckError(txt"""magnolia: could not find Show.Typeclass for type Double
        |    in parameter 'integer' of product type Alpha
        |    in parameter 'alpha' of product type Beta
        |"""))

    test("not attempt to instantiate Unit when producing error stack") {
      scalac"""
        case class Gamma(unit: Unit)
        Show.gen[Gamma]
      """
    }.assert(_ == TypecheckError(txt"""magnolia: could not find Show.Typeclass for type Unit
        |    in parameter 'unit' of product type Gamma
        |"""))

    test("typenames and labels are not encoded") {
      implicitly[Show[String, `%%`]].show(`%%`(1, "two"))
    }.assert(_ == "%%(/=1,#=two)")

    val tupleDerivation = test("derive for a tuple") {
      implicitly[Show[String, (Int, String)]]
    }.returns()

    test("serialize a tuple") {
      tupleDerivation().show((42, "Hello World"))
    }.assert(_ == "Tuple2(_1=42,_2=Hello World)")

    test("serialize a value class") {
      Show.gen[Length].show(new Length(100))
    }.assert(_ == "100")

    // Corrupt being covariant in L <: Seq[Company] enables the derivation for Corrupt[String, _]
    test("show a Politician with covariant lobby") {
      Show.gen[Politician[String]].show(Corrupt("wall", Seq(Company("Alice Inc"))))
    }.assert(_ == "Corrupt(slogan=wall,lobby=[Company(name=Alice Inc)])")

    // LabelledBox being invariant in L <: String prohibits the derivation for LabelledBox[Int, _]
    test("can't show a Box with invariant label") {
      scalac"Show.gen[Box[Int]]"
    }.assert { _ == TypecheckError(txt"""magnolia: could not find Show.Typeclass for type L
        |    in parameter 'label' of product type magnolia.tests.LabelledBox[Int, _ <: String]
        |    in coproduct type magnolia.tests.Box[Int]
        |""") }

    test("patch a Person via a Patcher[Entity]") {
      // these two implicits can be removed once https://github.com/propensive/magnolia/issues/58 is closed
      implicit val stringPatcher = Patcher.forSingleValue[String]
      implicit val intPatcher = Patcher.forSingleValue[Int]

      val person = Person("Bob", 42)
      implicitly[Patcher[Entity]].patch(person, Seq(null, 21))
    }.assert(_ == Person("Bob", 21))

    test("throw on an illegal patch attempt with field count mismatch") {
      // these two implicits can be removed once https://github.com/propensive/magnolia/issues/58 is closed
      implicit val stringPatcher = Patcher.forSingleValue[String]
      implicit val intPatcher = Patcher.forSingleValue[Int]

      try {
        val person = Person("Bob", 42)
        implicitly[Patcher[Entity]].patch(person, Seq(null, 21, 'killer))
      } catch {
        case NonFatal(e) => e.getMessage
      }
    }.assert(_ == "Cannot patch value `Person(Bob,42)`, expected 2 fields but got 3", _ => "test failed")

    test("throw on an illegal patch attempt with field type mismatch") {
      // these two implicits can be removed once https://github.com/propensive/magnolia/issues/58 is closed
      implicit val stringPatcher = Patcher.forSingleValue[String]
      implicit val intPatcher = Patcher.forSingleValue[Int]

      try {
        val person = Person("Bob", 42)
        implicitly[Patcher[Entity]].patch(person, Seq(null, 'killer))
      } catch {
        case NonFatal(e) => e.getMessage
      }
    }.assert(_ == "scala.Symbol cannot be cast to java.lang.Integer")

    class ParentClass {
      case class InnerClass(name: String)
      case class InnerClassWithDefault(name: String = "foo")

      def testInner(): Unit = {
        test("serialize a case class inside another class") {
          implicitly[Show[String, InnerClass]].show(InnerClass("foo"))
        }.assert(_ == "InnerClass(name=foo)")

        test("construct a default case class inside another class") {
          Default.gen[InnerClassWithDefault].default
        }.assert(_ == InnerClassWithDefault())

        ()
      }

      def testLocal(): Unit = {
        case class LocalClass(name: String)
        case class LocalClassWithDefault(name: String = "foo")

        test("serialize a case class inside a method") {
          implicitly[Show[String, LocalClass]].show(LocalClass("foo"))
        }.assert(_ == "LocalClass(name=foo)")

        test("construct a default case class inside a method") {
          Default.gen[LocalClassWithDefault].default
        }.assert(_ == LocalClassWithDefault())

        ()
      }
    }

    val parent = new ParentClass()
    parent.testInner()
    parent.testLocal()

    test("show an Account") {
      Show.gen[Account].show(Account("john_doe", "john.doe@yahoo.com", "john.doe@gmail.com"))
    }.assert(_ == "Account(id=john_doe,emails=[john.doe@yahoo.com,john.doe@gmail.com])")

    test("construct a default Account") {
      Default.gen[Account].default
    }.assert(_ == Account(""))

    test("show a Portfolio of Companies") {
      Show.gen[Portfolio].show(Portfolio(Company("Alice Inc"), Company("Bob & Co")))
    }.assert(_ == "Portfolio(companies=[Company(name=Alice Inc),Company(name=Bob & Co)])")

    test("show a List[Int]") {
      Show.gen[List[Int]].show(List(1, 2, 3))
    }.assert(_ == "::(head=1,tl$access$1=::(head=2,tl$access$1=::(head=3,tl$access$1=Nil())))")

    test("sealed trait typeName should be complete and unchanged") {
      TypeNameInfo.gen[Color].name
    }.assert(_.full == "magnolia.tests.Color")

    test("case class typeName should be complete and unchanged") {
      implicit val stringTypeName: TypeNameInfo[String] = new TypeNameInfo[String] {
        def name = ???
      }
      TypeNameInfo.gen[Fruit].name
    }.assert(_.full == "magnolia.tests.Fruit")

    test("show chained error stack") {
      scalac"""
        Show.gen[(Int, Seq[(Long, String)])]
      """
    } assert { _ == TypecheckError(txt"""magnolia: could not find Show.Typeclass for type Long
        |    in parameter '_1' of product type (Long, String)
        |    in chained implicit Show.Typeclass for type Seq[(Long, String)]
        |    in parameter '_2' of product type (Int, Seq[(Long, String)])
        |""") }

    test("show a recursive case class") {
      Show.gen[Recursive].show(Recursive(Seq(Recursive(Nil))))
    }.assert(_ == "Recursive(children=[Recursive(children=[])])")

    test("manually derive a recursive case class instance") {
      implicit lazy val showRecursive: Show[String, Recursive] = Show.gen[Recursive]
      showRecursive.show(Recursive(Seq(Recursive(Nil))))
    }.assert(_ == "Recursive(children=[Recursive(children=[])])")

    test("show a type aliased case class") {
      type T = Person
      Show.gen[T].show(Person("Donald Duck", 313))
    }.assert(_ == "Person(name=Donald Duck,age=313)")

    test("dependencies between derived type classes") {
      implicit def showDefaultOption[A](
        implicit showA: Show[String, A],
        defaultA: Default[A]
      ): Show[String, Option[A]] = new Show[String, Option[A]] {
        def show(optA: Option[A]) = showA.show(optA.getOrElse(defaultA.default))
      }

      Show.gen[Path[String]].show(OffRoad(Some(Crossroad(Destination("A"), Destination("B")))))
    }.assert(_ == "OffRoad(path=Crossroad(left=Destination(value=A),right=Destination(value=B)))")
  }
}
