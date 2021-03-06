package adept.resolution

import org.scalatest.FunSuite
import org.scalatest.Matchers
import adept.resolution.models._
import adept.repository._
import adept.test.TestDetails

class ResolverTest extends FunSuite with Matchers {
  import adept.test.ResolverUtils._
  import adept.test.OutputUtils._
  import adept.test.BenchmarkUtils._

  test("Very simple resolution works correctly") {
    val variants: Set[Variant] = Set(

      Variant("A", Set(version -> Set("V")),
        requirements = Set("B" -> Set.empty[Constraint])),

      Variant("B", Set(version -> Set("X")),
        requirements = Set("B" -> Set.empty[Constraint])))

    val requirements: Set[Requirement] = Set(
      "A" -> Set(Constraint(version, Set("V"))))

    val result = resolve(requirements, getMemoryLoader(variants))
    checkResolved(result, Set("A", "B"))
    checkVariants(result, "A", version -> Set("V"))
    checkVariants(result, "B", version -> Set("X"))
  }

  test("Internal combinations method works as expected") {
    val d10 = Variant("D", Set(version -> Set("1.0")))
    val d20 = Variant("D", Set(version -> Set("2.0")))
    val e10 = Variant("E", Set(version -> Set("1.0")))

    val variants: Set[Variant] = Set(
      Variant("A", Set(version -> Set("1.0"))),
      Variant("A", Set(version -> Set("2.0"))),
      Variant("B", Set(version -> Set("1.0"))),
      Variant("B", Set(version -> Set("2.0"))),
      Variant("C", Set(version -> Set("1.0"))),
      Variant("C", Set(version -> Set("2.0"))),
      d10,
      d20,
      e10)

    val resolver = new Resolver(getMemoryLoader(variants))

    val combinations = resolver.combinations(Set(new Id("D"), new Id("E")), Set.empty, Map.empty).map(_.toSet).toList
    combinations(0) shouldEqual Set(List(d10), List(d20), List(e10))
    combinations(1) shouldEqual Set(List(d10, e10), List(d20, e10))
  }

  test("All transitive variants are resolved correctly") {
    val variants: Set[Variant] = Set(

      Variant("A", Set(version -> Set("V")),
        requirements = Set("B" -> Set.empty[Constraint])),

      Variant("B", Set(version -> Set("X")),
        requirements = Set("C" -> Set[Constraint](version -> Set("X")))),

      Variant("C", Set(version -> Set("X")),
        requirements = Set("D" -> Set.empty[Constraint])),
      Variant("C", Set(version -> Set("Y")),
        requirements = Set("D" -> Set.empty[Constraint])),

      Variant("D", Set(version -> Set("Z")),
        requirements = Set.empty))

    val requirements: Set[Requirement] = Set(
      "A" -> Set(Constraint(version, Set("V"))))

    val result = resolve(requirements, getMemoryLoader(variants))
    checkResolved(result, Set("A", "B", "C", "D"))
    checkVariants(result, "D", version -> Set("Z"))
  }

  test("Simple under-constrained results behaves correctly") {
    val variants: Set[Variant] = Set(

      Variant("A", Set(version -> Set("1.0.0"), binaryVersion -> Set("1.0")),
        requirements = Set("B" -> Set[Constraint](binaryVersion -> Set("1.0")))),

      Variant("B", Set(version -> Set("1.0.0"), binaryVersion -> Set("1.0"))),
      Variant("B", Set(version -> Set("1.0.1"), binaryVersion -> Set("1.0"))))

    val requirements: Set[Requirement] = Set(
      "A" -> Set(Constraint(binaryVersion, Set("1.0"))))

    val result = resolve(requirements, getMemoryLoader(variants))
    checkResolved(result, Set("A"))
    checkUnresolved(result, Set("B"))
  }

  test("basic over-constrained") {
    val variants: Set[Variant] = Set(

      Variant("A", Set(version -> Set("1.0.0"), binaryVersion -> Set("1.0")),
        requirements = Set("B" -> Set[Constraint](binaryVersion -> Set("XXX")))),

      Variant("B", Set(version -> Set("1.0.0"), binaryVersion -> Set("1.0"))),
      Variant("B", Set(version -> Set("1.0.1"), binaryVersion -> Set("1.0"))))

    val requirements: Set[Requirement] = Set(
      "A" -> Set(Constraint(binaryVersion, Set("1.0"))))

    val result = resolve(requirements, getMemoryLoader(variants))
    checkResolved(result, Set("A"))
    checkUnresolved(result, Set("B"))
  }

  test("transitive, multi variant") {
    val variants: Set[Variant] = Set(

      Variant("A", Set(version -> Set("1.0.0"), binaryVersion -> Set("1.0")),
        requirements = Set("B" -> Set[Constraint]())),
      Variant("B", Set(version -> Set("1.0.0"), binaryVersion -> Set("1.0")),
        requirements = Set("C" -> Set[Constraint]())),
      Variant("C", Set(version -> Set("1.0.0"), binaryVersion -> Set("1.0")),
        requirements = Set("D" -> Set[Constraint]())),
      Variant("D", Set(version -> Set("1.0.0"), binaryVersion -> Set("1.0")),
        requirements = Set()))

    val requirements: Set[Requirement] = Set(
      "A" -> Set(Constraint(binaryVersion, Set("1.0"))))

    val result = resolve(requirements, getMemoryLoader(variants))
    checkResolved(result, Set("A", "B", "C", "D"))
    checkUnresolved(result, Set())
  }

  test("basic cyclic dependencies") {
    val variants: Set[Variant] = Set(

      Variant("A", Set(version -> Set("V")),
        requirements = Set("B" -> Set.empty[Constraint])),

      Variant("B", Set(version -> Set("X")),
        requirements = Set("A" -> Set[Constraint](version -> Set("V")))))

    val requirements: Set[Requirement] = Set(
      "A" -> Set(Constraint(version, Set("V"))),
      "B" -> Set(Constraint(version, Set("X"))))

    val result = resolve(requirements, getMemoryLoader(variants))
    checkResolved(result, Set("A", "B"))
    checkUnresolved(result, Set())
  }

  test("cyclic dependencies, multiple variants possible") {
    val variants: Set[Variant] = Set(

      Variant("A", Set(version -> Set("V")),
        requirements = Set("B" -> Set[Constraint](version -> Set("Y")))),

      Variant("B", Set(version -> Set("Y")),
        requirements = Set("B" -> Set[Constraint]())),

      Variant("A", Set(version -> Set("X")),
        requirements = Set("C" -> Set[Constraint](version -> Set("Z"))))) //does not exist

    val requirements: Set[Requirement] = Set(
      "A" -> Set(Constraint(version, Set("V"))),
      "B" -> Set(Constraint(version, Set("Y"))))

    val result = resolve(requirements, getMemoryLoader(variants))
    checkResolved(result, Set("A", "B"))
    checkUnresolved(result, Set())
  }

  test("nested constraints") {
    implicit val testDetails = TestDetails("Nested constraints")
    //B is unconstrained, but D forces C v 3.0, only B v 1.0 is constrained on C v 3.0 so B v 1.0 must be used:
    val variants: Set[Variant] = Set(

      Variant("A", Set(version -> Set("1.0.0"), binaryVersion -> Set("1.0")),
        requirements = Set(
          "B" -> Set[Constraint](),
          "C" -> Set[Constraint](),
          "D" -> Set[Constraint](),
          "E" -> Set[Constraint]())),

      Variant("B", Set(version -> Set("1.0.0"), binaryVersion -> Set("1.0")),
        requirements = Set("C" -> Set[Constraint](binaryVersion -> Set("2.0")))),
      Variant("B", Set(version -> Set("2.0.0"), binaryVersion -> Set("2.0")),
        requirements = Set("C" -> Set[Constraint](binaryVersion -> Set("3.0")))),

      Variant("C", Set(version -> Set("2.0.0"), binaryVersion -> Set("2.0")),
        requirements = Set()),
      Variant("C", Set(version -> Set("3.0.0"), binaryVersion -> Set("3.0")),
        requirements = Set()),

      Variant("D", Set(version -> Set("2.0.0"), binaryVersion -> Set("2.0")),
        requirements = Set("C" -> Set[Constraint](binaryVersion -> Set("2.0")))),
      Variant("D", Set(version -> Set("2.0.0"), binaryVersion -> Set("1.0")),
        requirements = Set("C" -> Set[Constraint](binaryVersion -> Set("3.0")))),

      Variant("E", Set(version -> Set("1.0.0"), binaryVersion -> Set("1.0")),
        requirements = Set(
          "D" -> Set[Constraint](binaryVersion -> Set("1.0")),
          "B" -> Set[Constraint]())))

    val requirements: Set[Requirement] = Set(
      "A" -> Set(Constraint(binaryVersion, Set("1.0"))))
    
      val loader = getMemoryLoader(variants)
    val result = benchmark(Resolved, requirements && loader) {
      resolve(requirements, loader)
    }
    checkResolved(result, Set("A", "B", "C", "D", "E"))
    checkUnresolved(result, Set())
  }

  test("nested under-constrained path find") {
    implicit val testDetails = TestDetails("Nested under-constrained path find")
    //B is under-constrained initially and so is F, but since E requires D v 1.0
    //and D 1.0 requires C 3.0, only B 2.0 and F 2.0 can be used with C 3.0
    //this graph should be resolved    val variants: Set[Variant] = Set(
    val variants: Set[Variant] = Set(

      Variant("A", Set(version -> Set("1.0.0"), binaryVersion -> Set("1.0")),
        requirements = Set(
          "B" -> Set[Constraint](),
          "C" -> Set[Constraint](),
          "D" -> Set[Constraint](),
          "E" -> Set[Constraint]())),

      Variant("B", Set(version -> Set("1.0.0"), binaryVersion -> Set("1.0")),
        requirements = Set(
          "C" -> Set[Constraint](binaryVersion -> Set("2.0")),
          "F" -> Set[Constraint]())),
      Variant("B", Set(version -> Set("2.0.0"), binaryVersion -> Set("2.0")),
        requirements = Set(
          "C" -> Set[Constraint](binaryVersion -> Set("3.0")), //requires C 3.0
          "F" -> Set[Constraint]())),

      Variant("C", Set(version -> Set("2.0.0"), binaryVersion -> Set("2.0")),
        requirements = Set()),
      Variant("C", Set(version -> Set("3.0.0"), binaryVersion -> Set("3.0")),
        requirements = Set()),

      Variant("D", Set(version -> Set("2.0.0"), binaryVersion -> Set("2.0")),
        requirements = Set("C" -> Set[Constraint](binaryVersion -> Set("2.0")))),
      Variant("D", Set(version -> Set("1.0.0"), binaryVersion -> Set("1.0")),
        requirements = Set("C" -> Set[Constraint](binaryVersion -> Set("3.0")))), //requires C 3.0

      Variant("E", Set(version -> Set("1.0.0"), binaryVersion -> Set("1.0")),
        requirements = Set("D" -> Set[Constraint](binaryVersion -> Set("1.0")))), //requires D 1.0

      Variant("F", Set(version -> Set("1.0.0"), binaryVersion -> Set("1.0")),
        requirements = Set("C" -> Set[Constraint](binaryVersion -> Set("2.0")))),
      Variant("F", Set(version -> Set("2.0.0"), binaryVersion -> Set("2.0")),
        requirements = Set("C" -> Set[Constraint](binaryVersion -> Set("3.0"))))) //requires C 3.0

    val requirements: Set[Requirement] = Set(
      "A" -> Set(Constraint(binaryVersion, Set("1.0"))))

    val result = benchmark(Resolved, requirements && getMemoryLoader(variants)) {
      resolve(requirements, getMemoryLoader(variants))
    }
    checkResolved(result, Set("A", "B", "C", "D", "E", "F"))
    checkUnresolved(result, Set())
  }

  test("basic under-constrained path find") {
    val variants: Set[Variant] = Set(

      Variant("A", Set(version -> Set("1.0.0"), binaryVersion -> Set("1.0")),
        requirements = Set(
          "B" -> Set[Constraint](),
          "C" -> Set[Constraint](binaryVersion -> Set("2.0")))),

      Variant("B", Set(version -> Set("1.0.0"), binaryVersion -> Set("1.0")),
        requirements = Set(
          "C" -> Set[Constraint](binaryVersion -> Set("2.0")))),
      Variant("B", Set(version -> Set("2.0.0"), binaryVersion -> Set("2.0")),
        requirements = Set(
          "C" -> Set[Constraint](binaryVersion -> Set("3.0")))),

      Variant("C", Set(version -> Set("2.0.0"), binaryVersion -> Set("2.0")),
        requirements = Set()),
      Variant("C", Set(version -> Set("3.0.0"), binaryVersion -> Set("3.0")),
        requirements = Set()))

    val requirements: Set[Requirement] = Set(
      "A" -> Set(Constraint(binaryVersion, Set("1.0"))))

    val result = resolve(requirements, getMemoryLoader(variants))
    checkResolved(result, Set("A", "B", "C"))
    checkUnresolved(result, Set())
    checkVariants(result, "A", version -> Set("1.0.0"), binaryVersion -> Set("1.0"))
    checkVariants(result, "B", version -> Set("1.0.0"), binaryVersion -> Set("1.0"))
    checkVariants(result, "C", version -> Set("2.0.0"), binaryVersion -> Set("2.0"))
  }

  test("multiple under-constrained paths find") {
    implicit val testDetails = TestDetails("Multiple under-constrained paths find")
    val variants: Set[Variant] = Set(

      Variant("A", Set(version -> Set("1.0.0"), binaryVersion -> Set("1.0")),
        requirements = Set(
          "B" -> Set[Constraint](),
          "C" -> Set[Constraint](),
          "D" -> Set[Constraint](),
          "E" -> Set[Constraint](binaryVersion -> Set("2.0")))),

      Variant("B", Set(version -> Set("1.0.0"), binaryVersion -> Set("1.0")),
        requirements = Set("E" -> Set[Constraint](binaryVersion -> Set("2.0")))),
      Variant("B", Set(version -> Set("2.0.0"), binaryVersion -> Set("2.0")),
        requirements = Set("E" -> Set[Constraint](binaryVersion -> Set("3.0")))),

      Variant("C", Set(version -> Set("1.0.0"), binaryVersion -> Set("1.0")),
        requirements = Set("E" -> Set[Constraint](binaryVersion -> Set("2.0")))),
      Variant("C", Set(version -> Set("2.0.0"), binaryVersion -> Set("2.0")),
        requirements = Set("E" -> Set[Constraint](binaryVersion -> Set("3.0")))),

      Variant("D", Set(version -> Set("1.0.0"), binaryVersion -> Set("1.0")),
        requirements = Set("E" -> Set[Constraint](binaryVersion -> Set("2.0")))),
      Variant("D", Set(version -> Set("2.0.0"), binaryVersion -> Set("2.0")),
        requirements = Set("E" -> Set[Constraint](binaryVersion -> Set("3.0")))),

      Variant("E", Set(version -> Set("2.0.0"), binaryVersion -> Set("2.0")),
        requirements = Set()),
      Variant("E", Set(version -> Set("3.0.0"), binaryVersion -> Set("3.0")),
        requirements = Set()))

    val requirements: Set[Requirement] = Set(
      "A" -> Set(Constraint(binaryVersion, Set("1.0"))))

    val loader = getMemoryLoader(variants)
    val result = benchmark(Resolved, requirements && loader) {
      resolve(requirements, loader)
    }
    checkResolved(result, Set("A", "B", "C", "D", "E"))
    checkUnresolved(result, Set())
    checkVariants(result, "A", version -> Set("1.0.0"), binaryVersion -> Set("1.0"))
    checkVariants(result, "B", version -> Set("1.0.0"), binaryVersion -> Set("1.0"))
    checkVariants(result, "C", version -> Set("1.0.0"), binaryVersion -> Set("1.0"))
    checkVariants(result, "D", version -> Set("1.0.0"), binaryVersion -> Set("1.0"))
    checkVariants(result, "E", version -> Set("2.0.0"), binaryVersion -> Set("2.0"))
  }

  test("basic exclusions") {
    val variants: Set[Variant] = Set(
      Variant("A", Set(version -> Set("V")),
        requirements = Set(
          "D" -> Set[Constraint](version -> Set("A")), //<- we really want D version A
          "B" -> Set.empty[Constraint])),

      Variant("B", Set(version -> Set("X")),
        requirements = Set(Requirement("C", Set(Constraint(version, Set("X"))), Set("D")))), //<- therefore we exclude D from C

      Variant("C", Set(version -> Set("X")),
        requirements = Set("D" -> Set[Constraint](version -> Set("Z")))), //<- because it requires version Z
      Variant("C", Set(version -> Set("Y")),
        requirements = Set("D" -> Set[Constraint](version -> Set("Z")))),

      Variant("D", Set(version -> Set("Z")),
        requirements = Set.empty),
      Variant("D", Set(version -> Set("A")),
        requirements = Set.empty))

    val requirements: Set[Requirement] = Set(
      "A" -> Set(Constraint(version, Set("V"))))

    val result = resolve(requirements, getMemoryLoader(variants))
    checkResolved(result, Set("A", "B", "C", "D"))
    checkExcluded(result, "D")
    checkVariants(result, "D", version -> Set("A"))
  }
}

