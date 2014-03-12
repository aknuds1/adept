package adept.repository

import adept.resolution.models._

/**
 * This rather boring class just loads the variants it has in memory
 * 
 * It is useful for testing.
 */
class MemoryLoader(variants: Set[Variant]) extends VariantsLoader {
  val variantsById = variants.groupBy(_.id) //avoid filtering ids that we know won't match

  def loadVariants(id: Id, constraints: Set[Constraint]): Set[Variant] = {
    AttributeConstraintFilter.filter(id, variantsById.get(id).getOrElse(Seq.empty).toSet, constraints)
  }

}