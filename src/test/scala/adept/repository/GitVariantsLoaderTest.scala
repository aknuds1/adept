package adept.repository

import org.scalatest.FunSuite
import org.scalatest.matchers.MustMatchers
import adept.repository.models.ConfiguredVariantsMetadata
import adept.repository.models.ConfiguredVariantsMetadataTestData
import adept.repository.models.MetadataContent
import java.io.File
import adept.models.Attribute

class GitVariantLoaderTest extends FunSuite with MustMatchers {
  import adept.test.FileUtils.usingTmpDir


  test("Basic reading from git repositories") {
    usingTmpDir { tmpDir =>
      import adept.repository.models.ConfiguredVariantsMetadataTestData.metadata

      val adeptGitRepo = new AdeptGitRepository(tmpDir, "adept-git-repo-basic")

      val commit1 = adeptGitRepo.updateMetadata({ content =>
        content.artifactsMetadata must be('empty)
        content.variantsMetadata must be('empty)
        Seq.empty
      }, { content =>
        content.artifactsMetadata must be('empty)
        content.variantsMetadata must be('empty)
        Seq(metadata.write(adeptGitRepo))
      }, "adding some new stuff")

      val commit2 = adeptGitRepo.updateMetadata({ content =>
        content.variantsMetadata must have size(1)
        content.artifactsMetadata must be('empty)
        content.variantsMetadata.toSeq.map{
          _.file(adeptGitRepo)
        }
      }, { content =>
        Seq(metadata.copy(attributes = metadata.attributes + Attribute("extra", Set("stuff"))).write(adeptGitRepo))
      }, "updating with extra stuff")

      commit1.canCompare(commit2) must be === true

      commit1 < commit2 must be === true
      commit1 > commit2 must be === false
      commit1 == commit2 must be === false
      commit2 < commit1 must be === false
      commit2 == commit1 must be === false
      commit2 > commit1 must be === true
    }
    
  }

}