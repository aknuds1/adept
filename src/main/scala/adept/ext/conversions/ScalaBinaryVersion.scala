package adept.ext.conversions

import adept.repository.models.ConfiguredVariantsMetadata
import adept.models._
import adept.ext.AttributeDefaults

object ScalaBinaryVersion extends Conversion {

  private lazy val ScalaLibBinaryVersionRegEx = """.*_(\d\.\d{2})$""".r
  private lazy val ScalaLibId = Id("scala-library")

  def convert(configuredVariant: ConfiguredVariantsMetadata): Option[ConfiguredVariantsMetadata] = {
    configuredVariant.id.value match {
      case ScalaLibBinaryVersionRegEx(scalaBinaryVersion) => {
        val configurations = configuredVariant.configurations.map { configuration =>
          val requirements = configuration.requirements.map { requirement =>
            if (requirement.id == ScalaLibId) {
              requirement.copy(constraints = requirement.constraints + Constraint(AttributeDefaults.BinaryVersionAttribute, Set(scalaBinaryVersion)))
            } else requirement
          }
          configuration.copy(requirements = requirements)
        }
        Some(configuredVariant.copy( configurations = configurations ))
      }
      case _ => Some(configuredVariant)
    }
  }

}