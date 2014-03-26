package adept.ext

import java.io.File
import adept.resolution.models.Id
import adept.repository.models.RepositoryName
import adept.repository.models.VariantHash
import adept.repository.models.Commit
import adept.repository.GitRepository
import adept.repository.serialization.VariantMetadata
import adept.resolution.models.Attribute
import adept.resolution.models.Requirement
import adept.repository.models.RepositoryLocations
import adept.repository.serialization.RepositoryLocationsMetadata
import adept.repository.serialization.ResolutionResultsMetadata
import adept.repository.models.Ranking
import adept.repository.serialization.RankingMetadata
import adept.repository.RankLogic
import adept.repository.models.ResolutionResult
import adept.repository.serialization.ArtifactMetadata
import adept.artifact.models.Artifact
import adept.repository.Repository
import adept.repository.models.RankId
import adept.logging.Logging

object VariantRename extends Logging {
  private case class VariantAssociations(variant: VariantMetadata, resolutionResults: Set[ResolutionResult], repositoryLocations: Set[RepositoryLocations], artifactLocations: Set[Artifact])
  private def getAllVariantAssociations(id: Id, variant: VariantHash, repository: GitRepository, commit: Commit): Option[VariantAssociations] = {
    for {
      variantMetadata <- VariantMetadata.read(id, variant, repository, commit)
      resolutionResultsMetadata = ResolutionResultsMetadata.read(id, variant, repository, commit).getOrElse(ResolutionResultsMetadata(Seq()))
      repositoryLocations = {
        resolutionResultsMetadata.values.flatMap { resolutionResult =>
          val name = resolutionResult.repository
          RepositoryLocationsMetadata.read(name, repository, commit).map(_.toRepositoryLocations(name))
        }
      }
      artifactLocations = {
        variantMetadata.artifacts.flatMap { artifactRef =>
          ArtifactMetadata.read(artifactRef.hash, repository, commit).map(_.toArtifact(artifactRef.hash))
        }
      }
    } yield {
      VariantAssociations(
        variant = variantMetadata,
        resolutionResults = resolutionResultsMetadata.values.toSet,
        repositoryLocations = repositoryLocations.toSet,
        artifactLocations = artifactLocations.toSet)
    }
  }

  def rename(baseDir: File, sourceId: Id, sourceName: RepositoryName, sourceCommit: Commit, destId: Id, destName: RepositoryName): (Commit, Commit) = { //source, dest
    logger.warn("Renaming is EXPERIMENTAL")
    val sourceRepository = new GitRepository(baseDir, sourceName)
    val destRepository = new GitRepository(baseDir, destName)
    if (!destRepository.exists) throw new Exception("Could not rename to " + destName + " because repository: " + destRepository.exists + " is not initialized")

    val allSourceVariantsRankIds = RankingMetadata.listRankIds(sourceId, sourceRepository, sourceCommit).toSeq.flatMap { rankId =>
      RankingMetadata.read(sourceId, rankId, sourceRepository, sourceCommit).toSeq
        .map(ranking => ranking.variants)
        .map(_ -> rankId)
    }
    val destRankIds = RankingMetadata.getXRankId(destId, destRepository, 0, allSourceVariantsRankIds.size)

    assert(allSourceVariantsRankIds.size == destRankIds.size)

    var sourceFiles = Set.empty[File]
    var destFiles = Set.empty[File]
    var newDestHashes = Set.empty[VariantHash]

    val redirectAttribute = Attribute("redirect", Set(sourceName.value + Repository.IdDirSep + sourceId.value + ":" + destName.value + Repository.IdDirSep + destId.value))
    val redirectRequirement = Requirement(destId, constraints = Set.empty, Set.empty)
    val redirectMetadata = VariantMetadata(
      attributes = Seq(redirectAttribute),
      artifacts = Seq.empty,
      requirements = Seq(redirectRequirement))
    val conflictRequirement =
      Requirement(sourceId, constraints = Set.empty, Set.empty)

    //gather files:
    destRepository.getRemoteUri(GitRepository.DefaultRemote).foreach { uri =>
      sourceFiles += RepositoryLocationsMetadata(Seq(uri)).write(destRepository.name, sourceRepository)
    }

    def addRankingIfNonExisting(id: Id, hashes: Seq[VariantHash], rankId: RankId, repository: GitRepository, commit: Commit) = {
      val formerRankings = RankingMetadata.read(id, rankId, repository, commit).getOrElse(RankingMetadata(Seq.empty))
      if (!formerRankings.variants.exists(hashes.contains(_))) {
        Some(RankingMetadata(hashes ++ formerRankings.variants).write(id, rankId, repository))
      } else None
    }
    var allResolutionResults = Set.empty[(VariantHash, Set[ResolutionResult])]
    (allSourceVariantsRankIds zip destRankIds).foreach {
      case ((sourceHashes, sourceRankId), destRankId) =>
        var newSourceHashes = IndexedSeq.empty[VariantHash] //appending so must be indexed

        sourceHashes.foreach { sourceHash =>
          val VariantAssociations(variant, resolutionResults, repositoryLocations, artifactLocations) =
            getAllVariantAssociations(sourceId, sourceHash, sourceRepository, sourceCommit)
              .getOrElse(throw new Exception("Could not find associated data for: " + sourceHash + " in " + sourceId + " in repo: " + sourceRepository.dir.getAbsolutePath + " for " + sourceCommit))
          val newSourceVariant = variant.copy(attributes = variant.attributes :+ redirectAttribute, requirements = variant.requirements.filter(_.id != destId) :+ conflictRequirement)
          destFiles += newSourceVariant.write(destId, destRepository)
          destFiles ++= repositoryLocations.map(repositoryLocation => RepositoryLocationsMetadata(repositoryLocation.uris.toSeq).write(repositoryLocation.name, destRepository))
          destFiles ++= artifactLocations.map(artifact => ArtifactMetadata.fromArtifact(artifact).write(artifact.hash, destRepository))

          allResolutionResults += newSourceVariant.hash -> resolutionResults //adding current results
          newSourceHashes = newSourceHashes :+ newSourceVariant.hash
        }
        newDestHashes += newSourceHashes.head

        destFiles ++= addRankingIfNonExisting(destId, newSourceHashes, destRankId, destRepository, destRepository.getHead)
        sourceFiles += redirectMetadata.write(sourceId, sourceRepository)
        sourceFiles ++= addRankingIfNonExisting(sourceId, Seq(redirectMetadata.hash), sourceRankId, sourceRepository, sourceCommit)
    }

    val oldDestRankId = {
      val formerSourceDestActiveVariants = RankLogic.getActiveVariants(sourceId, destRepository, destRepository.getHead)
      if (formerSourceDestActiveVariants.nonEmpty && formerSourceDestActiveVariants != Set(redirectMetadata.hash)) throw new Exception("Cannot rename because " + sourceId + " in " + destRepository.dir.getAbsolutePath + " exists for: " + destRepository.getHead + " and " + formerSourceDestActiveVariants + " is not equal to: " + Seq(redirectMetadata.hash))
      else RankingMetadata.getXRankId(sourceId, destRepository, 0, 1).head
    }
    destFiles += RankingMetadata(Seq(redirectMetadata.hash)).write(sourceId, oldDestRankId, destRepository)
    destFiles += redirectMetadata.write(sourceId, destRepository)

    destRepository.add(destFiles)
    val prevCommit = destRepository.getHead
    val addDestCommit = destRepository.commit("Added files from " + sourceRepository.name.value + " " + sourceId.value + " to " + destRepository.name.value + " " + destId.value)

    if (prevCommit != addDestCommit) {
      sourceFiles ++= newDestHashes.flatMap { hash =>
        Set() +
          ResolutionResultsMetadata(Seq(ResolutionResult(sourceId, destName, addDestCommit, hash))).write(sourceId, redirectMetadata.hash, sourceRepository) +
          ResolutionResultsMetadata(Seq(ResolutionResult(destId, destName, addDestCommit, redirectMetadata.hash))).write(sourceId, redirectMetadata.hash, sourceRepository)
      }
      destRepository.add(for {
        (newSourceHash, resolutionResults) <- allResolutionResults
      } yield {
        ResolutionResultsMetadata((resolutionResults + ResolutionResult(sourceId, destRepository.name, addDestCommit, redirectMetadata.hash)).toSeq).write(destId, newSourceHash, destRepository)
      })
      val createdDestCommit = destRepository.commit("Renamed from " + sourceRepository.name.value + " " + sourceId.value + " to " + destRepository.name.value + " " + destId.value)
      sourceRepository.add(sourceFiles)
      val createdSourceCommit = sourceRepository.commit("Renamed to " + sourceRepository.name.value + " " + sourceId.value + " to " + destRepository.name.value + " " + destId.value)
      createdSourceCommit -> createdDestCommit
      
    } else {
      sourceRepository.getHead -> prevCommit
    }
  }
}