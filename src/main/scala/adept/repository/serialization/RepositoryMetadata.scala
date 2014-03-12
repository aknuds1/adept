package adept.repository.serialization

import adept.repository.models._
import adept.resolution.models._
import adept.repository.Repository
import play.api.libs.json._
import play.api.libs.functional.syntax._
import java.io.BufferedWriter
import java.io.FileWriter
import adept.repository.GitRepository
import java.io.File

case class RepositoryMetadata(repositories: Seq[RepositoryInfo]) {
  import RepositoryMetadata._
  lazy val jsonString = Json.prettyPrint(Json.toJson(repositories))

  def write(id: Id, hash: VariantHash, repository: Repository): File = {
    val file = repository.getRepositoryFile(id, hash)
    MetadataContent.write(jsonString, file)
  }
}

object RepositoryMetadata {

  private[adept] implicit def format: Format[RepositoryInfo] = {
    (
      (__ \ "id").format[String] and
      (__ \ "repository").format[String] and
      (__ \ "commit").format[String] and
      (__ \ "variants").format[Set[String]])({
        case (id, repository, commit, variants) =>
          RepositoryInfo(
            Id(id),
            RepositoryName(repository),
            Commit(commit),
            VariantSet(variants.map(VariantHash(_))))
      },
        unlift({ r: RepositoryInfo =>
          val RepositoryInfo(id, repository, commit, variants) = r
          Some((
            id.value,
            repository.value,
            commit.value,
            variants.hashes.map(_.value)))
        }))
  }

  def read(id: Id, hash: VariantHash, repository: GitRepository, commit: Commit): Option[RepositoryMetadata] = {
    repository.usingRepositoryInputStream(id, hash, commit) {
      case Right(Some(is)) =>
        val json = Json.parse(io.Source.fromInputStream(is).getLines.mkString("\n"))
        Json.fromJson[Seq[RepositoryInfo]](json) match {
          case JsSuccess(values, _) => Some(RepositoryMetadata(values))
          case JsError(errors) => throw new Exception("Could parse json: " + id + "#" + hash + " for commit: " + commit + " in dir:  " + repository.dir + " (" + repository.getRepositoryFile(id, hash).getAbsolutePath + "). Got errors: " + errors)
        }
      case Right(None) => None
      case Left(error) =>
        throw new Exception("Could not read: " + id + "#" + hash + " for commit: " + commit + " in dir:  " + repository.dir + ". Got error: " + error)
    }
  }
}