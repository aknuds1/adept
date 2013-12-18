package adept.console

import org.scalatest._

/**
 * Specifications for the adept init command.
 */
class InitSpec extends FeatureSpec with GivenWhenThen {
  info("As a developer")
  info("I want to be able to initialize Adept support in a project")
  info("So I can use Adept for dependency management within said project")

  feature("Initialize") {
    scenario("User invokes init within project without Adept data") {
      // TODO Auto-remove project directory before test
      Given("we are within a software development project")
      // TODO: Create project directory and enter it
      // TODO: Enter project directory
      When("adept init is invoked")
      // TODO: Execute adept init
      Then("the project should be initialized with Adept data")
      // TODO: Verify that Adept data are added to the project
    }
  }
}
