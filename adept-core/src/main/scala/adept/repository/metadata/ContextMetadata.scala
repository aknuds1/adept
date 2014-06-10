package adept.repository.metadata

import adept.repository.models._
import adept.resolution.models._
import adept.repository.Repository
import java.io.BufferedWriter
import java.io.FileWriter
import adept.repository.GitRepository
import java.io.File
import net.minidev.json.{JSONArray, JSONObject}
import collection.JavaConverters._

case class ContextMetadata(values: Seq[ContextValue]) {
  import ContextMetadata._
  lazy val jsonString = toJson(values.sorted)

  def write(id: Id, hash: VariantHash, repository: Repository): File = {
    val file = repository.ensureContextFile(id, hash)
    MetadataContent.write(jsonString, file)
  }

  private def toJson(context: Seq[ContextValue]) : String = {
    //  private[adept] implicit def format: Format[ContextValue] = {
    //    (
    //      (__ \ "id").format[String] and
    //      (__ \ "repository").format[String] and
    //      (__ \ "commit").format[Option[String]] and
    //      (__ \ "variant").format[String])({
    //        case (id, repository, commit, variant) =>
    //          ContextValue(
    //            Id(id),
    //            RepositoryName(repository),
    //            commit.map(Commit(_)),
    //            VariantHash(variant))
    //      },
    //        unlift({ cv: ContextValue =>
    //          val ContextValue(id, repository, commit, variant) = cv
    //          Some((
    //            id.value,
    //            repository.value,
    //            commit.map(_.value),
    //            variant.value))
    //        }))
    //  }

    val jsonArray = new JSONArray()
    jsonArray.addAll(
    context.map { contextValue =>
      val obj = new JSONObject()
      obj.put("id", contextValue.id.toString)
      obj.put("repository", contextValue.repository.value)
      obj.put("commit", contextValue.commit)
      obj.put("variant", contextValue.variant.value)
      obj
    }.asJava)
    jsonArray.toJSONString
  }
}

object ContextMetadata {
  def read(id: Id, hash: VariantHash, repository: Repository): Option[ContextMetadata] = {
    val file = repository.getContextFile(id, hash)
    repository.usingFileInputStream(file) {
      case Right(Some(is)) =>
        val json = Json.parse(io.Source.fromInputStream(is).getLines.mkString("\n"))
        Json.fromJson[Seq[ContextValue]](json) match {
          case JsSuccess(values, _) => Some(ContextMetadata(values))
          case JsError(errors) => throw new Exception("Could parse json: " + id + "#" + hash + " in dir:  " + repository.dir + " (" + file.getAbsolutePath() + "). Got errors: " + errors)
        }
      case Right(None) => None
      case Left(error) =>
        throw new Exception("Could not read: " + id + "#" + hash + " in dir:  " + repository.dir + ". Got error: " + error)
    }
  }

  def read(id: Id, hash: VariantHash, repository: GitRepository, commit: Commit): Option[ContextMetadata] = {
    repository.usingContextInputStream(id, hash, commit) {
      case Right(Some(is)) =>
        val json = Json.parse(io.Source.fromInputStream(is).getLines.mkString("\n"))
        Json.fromJson[Seq[ContextValue]](json) match {
          case JsSuccess(values, _) => Some(ContextMetadata(values))
          case JsError(errors) => throw new Exception("Could parse json: " + id + "#" + hash + " for commit: " + commit + " in dir:  " + repository.dir + " (" + repository.asGitPath(repository.getContextFile(id, hash)) + "). Got errors: " + errors)
        }
      case Right(None) => None
      case Left(error) =>
        throw new Exception("Could not read: " + id + "#" + hash + " for commit: " + commit + " in dir:  " + repository.dir + ". Got error: " + error)
    }
  }
}
