package adept.sbt

import sbt._
import sbt.Keys._
import sbt.complete.DefaultParsers._
import sbt.complete._
import play.api.libs.json.Json
import adept.repository.AdeptGitRepository
import adept.models._
import adept.repository.models.configuration.ConfiguredRequirement
import adept.repository.AdeptCommit
import adept.resolution.Resolver
import adept.repository.GitVariantsLoader
import adept.repository.models.Commit
import adept.repository.models.configuration.ConfiguredRequirement
import adept.repository.models.configuration.ConfigurationId
import net.sf.ehcache.CacheManager
import adept.repository.models.RepositoryMetadata
import adept.repository.models.configuration.ConfiguredRequirement

object AdeptRepository {
  import sbt.complete.DefaultParsers._

  def repositories = (Faked.baseDir / "repos").listFiles().filter(_.isDirectory).map(_.getName)

  def modules(repoName: String) = {
    (Faked.baseDir / "repos" / repoName / "variants").listFiles().filter(_.isDirectory).map(_.getName)
  }

  def repositoryParser: Parser[String] = {
    val candidates = repositories.toList.sorted
    if (candidates.size == 1) {
      candidates.head ^^^ candidates.head
    } else if (candidates.size > 1) {
      candidates.tail.foldLeft(candidates.head ^^^ candidates.head) { (currentParser, candidate) =>
        (candidate ^^^ candidate | currentParser)
      }
    } else {
      Parser.failure("No repositories found")
    }
  }

  def idParser(repository: String): Parser[String] = {
    import sbt.complete.DefaultParsers._
    val candidates = modules(repository).toList.sorted
    if (candidates.size == 1) {
      candidates.head ^^^ candidates.head
    } else if (candidates.size > 1) {
      candidates.tail.foldLeft(candidates.head ^^^ candidates.head) { (currentParser, candidate) =>
        (candidate ^^^ candidate | currentParser)
      }
    } else {
      Parser.failure("No ids found")
    }
  }
}

abstract class AdeptCommand {
  def execute(): Unit
}

class IvyImportCommand(org: String, name: String, revision: String) extends AdeptCommand {
  def execute(): Unit = {
    println("ivy import: " + org + ", " + name + ", " + revision)
  }
}

class InstallAdeptCommand(repo: String, conf: String, id: String, constraints: Set[(String, Seq[String])])(adeptManager: AdeptManager) extends AdeptCommand {
  import sbt.complete.DefaultParsers._

  def execute(): Unit = {
    adeptManager.install(repo, conf, id, constraints)
  }
}

package lockfile {
  package object serialization {
    implicit val configurationEntryFormat = Json.format[ConfigurationEntry]
    implicit val requirementEntryFormat = Json.format[RequirementEntry]
    implicit val artifactEntryFormat = Json.format[ArtifactEntry]
    implicit val lockfileFormat = Json.format[LockFile]
  }
  case class ConfigurationEntry(id: String, constraints: Set[Map[String, Seq[String]]], repository: String, commit: String)
  case class RequirementEntry(hash: String, configurations: Map[String, Seq[ConfigurationEntry]])

  case class ArtifactEntry(hash: String, variant: String, locations: Seq[String], size: Long)
  case class LockFile(requirements: Seq[RequirementEntry], artifacts: Seq[ArtifactEntry]) {
    def getResolveInfo: (Set[ConfiguredRequirement], Set[AdeptCommit]) = {
      ???
    }
  }
}

object LockFile {

  def read(file: File) = {
    if (file.exists() && file.isFile) {
      val source = io.Source.fromFile(file)
      import lockfile.serialization._
      Json.parse(source.getLines.mkString("\n")).as[lockfile.LockFile]
    } else lockfile.LockFile(Seq.empty, Seq.empty)
  }
}

object Faked { //REMOVE THIS when finished testing (used for hard coding)
  val baseDir = Path.userHome / ".adept"

  var fakeRequirements = Set.empty[ConfiguredRequirement]

  def fakeCommits = fakeRequirements.map { r =>
    AdeptCommit(new AdeptGitRepository(baseDir, r.commit.name), r.commit.commit)
  }
}

class AdeptManager(baseDir: File, lockFile: File) {

  def install(repo: String, conf: String, id: String, constraints: Set[(String, Seq[String])]) = {
    val parsedConstraints = constraints.map { case (name, values) => Constraint(name, values.toSet) }
    val gitRepo = new AdeptGitRepository(baseDir, repo)

    //val (configuredRequirements, commits) = LockFile.read(lockFile).getResolveInfo

    val newReq = ConfiguredRequirement(id = Id(id), configurations = Set(ConfigurationId("compile")), commit = RepositoryMetadata(repo, Set.empty, Commit("HEAD"), "ivy import"),
      constraints = parsedConstraints)
    Faked.fakeRequirements = Faked.fakeRequirements + newReq

    val gitVariantsLoader = new GitVariantsLoader(Faked.fakeCommits, cacheManager = CacheManager.create)
    val gitResolver = new Resolver(gitVariantsLoader)

    val requirements = Faked.fakeRequirements.flatMap(_.asRequirements)
    println(requirements)
    val result = gitResolver.resolve(requirements)

    println(result)
    if (result.state.isOverconstrained) {
      Faked.fakeRequirements -= newReq
    }

    //read lockfile and use all commits, (id, constraints)s and resolve
    //if resolves then create a new lockfile
    //else fail
  }
}

object AdeptPlugin extends Plugin {

  import AdeptKeys._

  def adeptSettings = Seq(
    adeptDirectory := Path.userHome / ".adept",
    sbt.Keys.commands += {

      val Install = token("install")
      val IvyImport = token("ivy-import")

      val RepositorySep = token("/")
      val adeptManager = new AdeptManager(adeptDirectory.value, new File("adept.lock"))

      val repositoires = token(Space ~> AdeptRepository.repositoryParser) flatMap { repo =>
        token(RepositorySep ~> AdeptRepository.idParser(repo)).flatMap { id =>
          token(Space ~> charClass(_ => true, "").*).map { binaryVersionChars =>
            val binaryVersion = binaryVersionChars.mkString.trim()

            val constraints =
              if (binaryVersion.isEmpty)
                Set.empty[(String, Seq[String])]
              else
                Set("binary-version" -> Seq(binaryVersion))

            new InstallAdeptCommand(repo, "compile", id, constraints)(adeptManager)

          }
        }
      }

      val install = Install ~> repositoires

      val ivyImport = (IvyImport ~> (Space ~> charClass(_ => true, "ivy-organization").+.flatMap { orgChars =>
        val org = orgChars.mkString
        token("#") ~> charClass(_ => true, "ivy-name").+.flatMap { nameChars =>
          val name = nameChars.mkString
          token(";") ~> charClass(_ => true, "ivy-revision").+.map { revisionChars =>
            val revision = revisionChars.mkString
            new IvyImportCommand(org, name, revision)
          }
        }
      }))

      val adept = (Space ~> (install | ivyImport))

      Command("adept")(_ => adept) { (state, adeptCommand) =>
        adeptCommand.execute()
        state
      }
    })
}