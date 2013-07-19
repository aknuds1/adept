package adept.core.models

import java.io.File
import org.json4s._
import org.json4s.Extraction._
import org.json4s.native.JsonMethods._
import org.json4s.JsonDSL._
import adept.utils.JsonHelpers._
import adept.utils.EitherUtils
import java.io.StringWriter
import java.io.PrintWriter
import adept.utils.Logging

case class Module(
  coordinates: Coordinates,
  uniqueId: UniqueId,
  artifacts: Set[Artifact],
  configurations: Set[Configuration],
  attributes: Map[String, Seq[String]],
  dependencies: Set[Dependency],
  overrides: Set[Override]) {
  //TODO: add created 
  //TODO: add universes ([scala-version:2.10])
}

object Module {

  def readSameCoordinates(json: JValue): Either[String, Seq[Module]] = {
    val res = for {
      coords <- Coordinates.readCoords(json).right
      modules <- readModulesWithUsingCoords(coords, json).right
    } yield {
      modules
    }
    res
  }

  def readJsonModule(json: JValue): Either[String, Module] = {
    for {
      modules <- readSameCoordinates(json).right
    } yield {
      if(modules.length == 1) {
        modules.head
      } else {
        throw new Exception("only 1 module expected")
      }
    }
  }

  def writeJsonForSameCoords(coords: Coordinates, modules: Seq[Module]): JValue = {
    Coordinates.coordsToJson(coords) ~ asJObject(List[JField](
      ("modules" -> modules.map { module =>
        if (coords != module.coordinates) throw new Exception("expected module: " + module + " to have coords: " + coords + ". modules: " + modules)
        noCoordsModuleToJson(module)
      })).map(ifNonEmpty): _*)
  }

  def writeJsonModule(module: Module): JValue = {
    writeJsonForSameCoords(module.coordinates, Seq(module))
  }
   

  def readModuleWithUsingCoords(coords: Coordinates, json: JValue): Either[String, Module] = {
    val maybeArtifacts = Artifact.readArtifacts((json \ "artifacts"))

    val maybeConfigurations = {
      (json \ "configurations").toOption.map(Configuration.readConfigurations)
    }.getOrElse{
      Right(Set.empty[Configuration])
    }

    val maybeDependencies = {
      (json \ "dependencies").toOption.map(Dependency.readDependencies)
    }.getOrElse{
      Right(Set.empty[Dependency])
    }

    val maybeOverrides = {
      (json \ "overrides").toOption.map(Override.readOverrides)
    }.getOrElse{
      Right(Set.empty[Override])
    }

    val attributes: Map[String, Seq[String]] = {
      implicit val format = org.json4s.DefaultFormats
      (json \ "attributes").extractOpt[Map[String, Seq[String]]]
    }.getOrElse{
      Map.empty
    }

    for {
      artifacts <- maybeArtifacts.right
      uniqueId <- (eitherOf[String](json) \ "unique-id").right
      configurations <- maybeConfigurations.right
      dependencies <- maybeDependencies.right
      overrides <- maybeOverrides.right
    } yield {
      Module(coords, UniqueId(uniqueId), artifacts, configurations, attributes, dependencies, overrides)
    }
  }

  def readModulesWithUsingCoords(coords: Coordinates, json: JValue): Either[String, Seq[Module]] = {
    readSeq(json \ "modules"){ f =>
      f.map(j => readModuleWithUsingCoords(coords, j))
    }
  }
  
  def noCoordsModuleToJson(module: Module): JObject = {
    asJObject(List[JField](
      ("unique-id" -> module.uniqueId.value),
      ("artifacts" -> module.artifacts.map(Artifact.artifactToJson)),
      ("attributes" -> module.attributes),
      ("dependencies" -> module.dependencies.map(Dependency.dependencyToJson)),
      ("overrides" -> module.overrides.map(Override.overrideToJson)),
      ("configurations" -> module.configurations.map(Configuration.configurationToJson))).map(ifNonEmpty): _*)
  }

}