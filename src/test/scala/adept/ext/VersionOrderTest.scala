package adept.ext

import org.scalatest.FunSpec
import org.scalatest.Matchers
import adept.repository.models._
import adept.repository.serialization._
import adept.repository._
import adept.resolution.models._
import net.sf.ehcache.CacheManager
import adept.repository.serialization.Order
import java.io.File
import org.scalatest.OptionValues._

class VersionOrderTest extends FunSpec with Matchers {
  import adept.test.FileUtils._
  import adept.test.ResolverUtils._

  describe("Creating binary versions") {
    val idA = Id("A")
    def binaryVersion(variant: Variant) = {
      usingTmpDir { tmpDir =>
        val repoA = new GitRepository(tmpDir, RepositoryName("com.a"))
        repoA.init()

        val variantMetadata = VariantMetadata.fromVariant(variant)
        repoA.add(variantMetadata.write(variant.id, repoA))
        repoA.commit("Added: " + variant.id)
        repoA.add(Order.insertNewFile(variant.id, variantMetadata.hash, repoA, repoA.getHead))
        repoA.commit("Order: " + variant.id)

        repoA.add(VersionOrder.useSemanticVersions(idA,
          VariantMetadata.fromVariant(variant).hash,
          repoA, repoA.getHead,
          useVersionAsBinary = Set("2\\.9.*?".r),
          excludes = Set("2\\.8.*?".r)))
        repoA.commit("SemVer")
        val activeAs = Order.activeVariants(idA, repoA, repoA.getHead)
        activeAs should have size (1)
        val hash = activeAs.headOption.value
        VariantMetadata.read(idA, hash, repoA, repoA.getHead).value.attribute(AttributeDefaults.BinaryVersionAttribute).values
      }
    }

    it("should work for regular version strings") {
      binaryVersion(Variant(idA, Set(version -> Set("2.10.1")))) shouldEqual Set("2.10")
    }
    it("should work for more 'exotic' version strings") {
      binaryVersion(Variant(idA, Set(version -> Set("2.11.1-SNAPSHOT")))) shouldEqual Set("2.11")
    }
    it("should use versions as binaries for matching versions") {
      binaryVersion(Variant(idA, Set(version -> Set("2.9.3")))) shouldEqual Set("2.9.3")
    }
    it("should skip versions that matches excludes") {
      binaryVersion(Variant(idA, Set(version -> Set("2.8.1")))) shouldEqual Set()
    }
  }

  describe("Using binary versions in OTHER variants") {
    it("should replace the latest variant with one that uses the binary version") {

      def addThenCommit(variant: Variant, repo: GitRepository, resolutionResults: Set[ResolutionResult]): Commit = {
        val variantMetadata = VariantMetadata.fromVariant(variant)
        repo.add(variantMetadata.write(variant.id, repo))
        repo.commit("Added: " + variant.id)
        repo.add(VersionOrder.orderBinaryVersions(variant.id, repo, repo.getHead))
        repo.add(ResolutionResultsMetadata(resolutionResults.toSeq).write(variant.id, variantMetadata.hash, repo))
        repo.commit("Order & repository metadata: " + variant.id)
      }

      usingTmpDir { tmpDir =>
        val repoA = new GitRepository(tmpDir, RepositoryName("com.a"))
        repoA.init()
        val repoB = new GitRepository(tmpDir, RepositoryName("com.b"))
        repoB.init()
        val repoC = new GitRepository(tmpDir, RepositoryName("com.c"))
        repoC.init()

        val idA = Id("A")
        val idB = Id("B")
        val idC = Id("C")

        val variantA = Variant(idA, Set(version -> Set("1.0.0"), binaryVersion -> Set("1.0")))
        val commitA = addThenCommit(variantA, repoA, Set())
        val hashA = VariantMetadata.fromVariant(variantA).hash
        val resolveResultA = ResolutionResult(idA, repoA.name, commitA, hashA)
        addThenCommit(Variant(idB, Set(version -> Set("1.0.0"), binaryVersion -> Set("1.0")),
          requirements = Set(Requirement(idA, Set.empty))), repoB,
          Set(resolveResultA))
        addThenCommit(Variant(idC, Set(version -> Set("1.0.0"), binaryVersion -> Set("1.0")),
          requirements = Set(Requirement(idA, Set.empty))), repoC,
          Set(resolveResultA))

        VersionOrder.useBinaryVersionOf(idA, repoA, commitA, inRepositories = Set(repoB, repoC)).foreach {
          case (repo, file) =>
            repo.add(file)
            repo.commit("Using binary version for: " + idA.value)
        }

        val activeBs = Order.activeVariants(idB, repoB, repoB.getHead)
        activeBs should have size (1)
        activeBs.map { hash =>
          val newVariant = VariantMetadata.read(idB, hash, repoB, repoB.getHead).value
          val requirements = newVariant.requirements.find(_.id == idA).value
          requirements.constraint(AttributeDefaults.BinaryVersionAttribute).values shouldEqual Set("1.0")
        }
        val activeCs = Order.activeVariants(idC, repoC, repoC.getHead)
        activeCs should have size (1)
        activeCs.map { hash =>
          val newVariant = VariantMetadata.read(idC, hash, repoC, repoC.getHead).value
          val requirements = newVariant.requirements.find(_.id == idA).value
          requirements.constraint(AttributeDefaults.BinaryVersionAttribute).values shouldEqual Set("1.0")
        }
      }
    }
  }

  describe("Variants with binary versions") {
    it("should be automatically re-ordered by orderBinaryVersions") {
      usingTmpDir { tmpDir =>
        val id = Id("A")
        val variant101 = Variant(id, Set(version -> Set("1.0.1"), binaryVersion -> Set("1.0")))
        val variant100 = Variant(id, Set(version -> Set("1.0.0"), binaryVersion -> Set("1.0")))
        val variant102 = Variant(id, Set(version -> Set("1.0.2"), binaryVersion -> Set("1.0")))
        val variant111 = Variant(id, Set(version -> Set("1.1.1"), binaryVersion -> Set("1.1")))
        val variant110 = Variant(id, Set(version -> Set("1.1.0"), binaryVersion -> Set("1.1")))
        val variant112 = Variant(id, Set(version -> Set("1.1.2"), binaryVersion -> Set("1.1")))

        val variant200 = Variant(id, Set(version -> Set("2.0.0"), binaryVersion -> Set("2.0")))

        val repository = new GitRepository(tmpDir, RepositoryName("com.a"))
        repository.init()
        repository.add(VariantMetadata.fromVariant(variant101).write(id, repository))
        repository.add(VariantMetadata.fromVariant(variant100).write(id, repository))
        repository.add(VariantMetadata.fromVariant(variant110).write(id, repository))
        repository.add(VariantMetadata.fromVariant(variant200).write(id, repository))

        val commit1 = repository.commit("Adding some data")
        repository.add(VersionOrder.orderBinaryVersions(id, repository, commit1))
        val commit2 = repository.commit("Order! Oooorder in the repo!")
        Order.chosenVariants(id, Set.empty, repository, commit2) shouldEqual Set(VariantMetadata.fromVariant(variant101).hash, VariantMetadata.fromVariant(variant110).hash, VariantMetadata.fromVariant(variant200).hash)

        repository.rm(repository.getVariantFile(id, VariantMetadata.fromVariant(variant200).hash))
        val commit3 = repository.commit("Remove some data...")

        repository.add(VariantMetadata.fromVariant(variant102).write(id, repository))
        repository.add(VariantMetadata.fromVariant(variant111).write(id, repository))
        repository.add(VariantMetadata.fromVariant(variant112).write(id, repository))
        val commit4 = repository.commit("Add some data...")

        repository.add(VersionOrder.orderBinaryVersions(id, repository, commit4))
        val commit5 = repository.commit("And some order")
        Order.chosenVariants(id, Set.empty, repository, commit5) shouldEqual Set(VariantMetadata.fromVariant(variant112).hash, VariantMetadata.fromVariant(variant102).hash)

        repository.add(VariantMetadata.fromVariant(variant200).write(id, repository))
        val commit6 = repository.commit("Re-added something we removed")
        repository.add(VersionOrder.orderBinaryVersions(id, repository, commit6))
        val commit7 = repository.commit("Adept: Now with more order!")
        
        Order.chosenVariants(id, Set.empty, repository, commit7) shouldEqual Set(VariantMetadata.fromVariant(variant112).hash, VariantMetadata.fromVariant(variant102).hash, VariantMetadata.fromVariant(variant200).hash)


      }
    }
  }

}