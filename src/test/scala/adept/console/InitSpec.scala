package adept.console

import org.scalatest._
import java.nio.file.{Path, Paths, Files}
import scala.sys.process.Process
import java.io.File

import FileHelper._

/**
 * Specifications for the adept init command.
 */
class InitSpec extends FeatureSpec with GivenWhenThen with Matchers {
  info("As a developer")
  info("I want to be able to initialize Adept support in a project")
  info("So I can use Adept for dependency management within said project")

  feature("Initialize") {
    scenario("User invokes init within project without Adept data") {
      val workingDir = Paths.get("tmp/spec")
      workingDir.toFile().deleteAll

      Given("we are within a software development project")
      Files.createDirectories(workingDir)

      When("adept init is invoked")
      val exitCode = Process("../../bin/adept init", new File(workingDir.toString)).!

      Then("Adept should finish without errors")
      exitCode should === (0)

      And("the project should be initialized with Adept data")
      val adeptDir = new File(workingDir.toFile, ".adept")
      adeptDir should be a 'directory
    }
  }
}
