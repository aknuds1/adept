package adept.test

import adept.resolution._
import adept.resolution.models._
import adept.resolution.resolver.models._
import adept.repository._
import org.scalatest.Matchers

object ResolverUtils extends Matchers {
  import scala.language.implicitConversions //this is test code

  val version = "version"
  val binaryVersion = "binary-version"

  implicit def stringToId(id: String): Id = {
    Id(id)
  }

  implicit def tuple2ToAttribute(t: (String, Set[String])): Attribute = {
    val (name, values) = t
    Attribute(name, values)
  }

  implicit def tuple2ToConstraint(t: (String, Set[String])): Constraint = {
    val (name, values) = t
    Constraint(name, values)
  }

  implicit def tuple2ToRequirement(t: (String, Set[Constraint])): Requirement = {
    val (name, constraints) = t
    Requirement(name, constraints, Set.empty)
  }

  def checkResolved(result: ResolveResult, ids: Set[Id]) = {
    val state = result.state
    val found = (state.resolved ++ state.implicitVariants.keys)
    if (found != ids)
      println("Assertion will fail.\nFound diff is: " + found.diff(ids) + "\nExpected diff is: " + ids.diff(found))
    (state.resolved ++ state.implicitVariants.keys) shouldEqual (ids)
  }

  def checkUnresolved(result: ResolveResult, ids: Set[Id]) = {
    val state = result.state
    val underconstrained = state.underconstrained
    val overconstrained = state.overconstrained
    (overconstrained ++ underconstrained) shouldEqual (ids)
  }

  def checkAttributeVariants(result: ResolveResult, id: Id, attr: Attribute) = {
    import org.scalatest.OptionValues._
    assert(result.state.isResolved, "Could not find resolved state:\n" + result)
    val state = result.state
    val variant = (state.resolvedVariants ++ state.implicitVariants).get(id).value
    variant.id shouldEqual (id)
    (id -> variant.attribute(attr.name)) shouldEqual (id -> attr)
  }

  def checkVariants(result: ResolveResult, id: Id, attrs: Attribute*) = {
    import org.scalatest.OptionValues._
    assert(result.state.isResolved, "Could not find resolved state:\n" + result)
    val state = result.state
    val variant = (state.resolvedVariants ++ state.implicitVariants).get(id).value
    variant.id shouldEqual (id)
    (id -> variant.attributes) shouldEqual (id -> attrs.toSet)
  }
  
  def checkExcluded(result: ResolveResult, id: Id) = {
    import org.scalatest.OptionValues._
    val state = result.state
    state.excluded should contain(id)
  }

  def getMemoryLoader(variants: Set[Variant]) = {
    new MemoryLoader(variants)
  }

  def resolve(requirements: Set[Requirement], loader: VariantsLoader): ResolveResult = {
    val resolver = new Resolver(loader)
    resolver.resolve(requirements)
  }
}