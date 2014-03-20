package adept.ivy

import adept.ext.AttributeDefaults
import adept.ext.VersionScanner
import org.apache.ivy.Ivy
import org.apache.ivy.core.module.id.ModuleRevisionId
import org.apache.ivy.core.resolve.ResolveOptions
import org.apache.ivy.core.module.descriptor.{ Artifact => IvyArtifact }
import org.apache.ivy.core.module.descriptor.{ Configuration => IvyConfiguration }
import org.apache.ivy.util.Message
import org.apache.ivy.util.DefaultMessageLogger
import org.apache.ivy.core.IvyContext
import java.io.File
import org.apache.ivy.core.resolve.IvyNode
import collection.JavaConverters._
import org.apache.ivy.core.module.descriptor.Configuration.Visibility
import org.apache.ivy.core.report.ResolveReport
import org.apache.ivy.util.AbstractMessageLogger
import adept.ext.Version
import adept.logging.Logging
import adept.artifact.models._
import adept.repository.models._
import adept.resolution.resolver.models._
import adept.resolution.models._
import adept.utils.Hasher
import java.io.FileInputStream
import adept.repository.serialization.VariantMetadata
import adept.repository.GitRepository
import adept.ext.VersionOrder
import adept.repository.serialization.ResolutionResultsMetadata
import org.eclipse.jgit.lib.ProgressMonitor
import org.apache.ivy.plugins.matcher.MapMatcher
import org.apache.ivy.core.module.descriptor.OverrideDependencyDescriptorMediator
import org.apache.ivy.plugins.matcher.PatternMatcher
import org.apache.ivy.core.IvyPatternHelper
import org.apache.ivy.core.module.descriptor.{ ModuleDescriptor, DependencyDescriptor }
import org.apache.ivy.core.cache.ResolutionCacheManager
import org.apache.ivy.core.module.id.ModuleId
import org.apache.ivy.core.module.descriptor.ExcludeRule

case class AdeptIvyResolveException(msg: String) extends Exception(msg)
case class AdeptIvyException(msg: String) extends Exception(msg)

case class IvyVerficationErrorReport(msg: String, adeptExtraArtifacts: Map[ArtifactHash, Variant], ivyExtraArtifacts: Map[ArtifactHash, ModuleRevisionId], nonMatchingArtifacts: Set[(ArtifactHash, Variant, Set[ArtifactHash])])

object IvyHelper extends Logging {
  import AttributeDefaults.{ NameAttribute, OrgAttribute, VersionAttribute }
  val ConfigurationHashAttribute = "configuration-hash"
  val ConfigurationAttribute = "configuration"
  val ArtifactConfAttribute = "configurations"
  val IdConfig = "config"

  lazy val errorIvyLogger = new DefaultMessageLogger(Message.MSG_ERR) {
    var i = 0

    override def doProgress(): Unit = {
      val indicator = if (i == 0) "-"
      else if (i == 1) "/"
      else if (i == 2) "-"
      else if (i == 3) "\\"
      else if (i == 4) "|"
      else {
        i = 0
        "/"
      }
      i = i + 1
      //System.out.print("\r" * 80 + " " * 80 + "\r" * 80)
      System.out.print(indicator + "\r")
    }

    override def doEndProgress(ivyMsg: String): Unit = {
      //pass
    }
  }
  lazy val warnIvyLogger = new DefaultMessageLogger(Message.MSG_WARN)
  lazy val infoIvyLogger = new DefaultMessageLogger(Message.MSG_INFO)
  lazy val debugIvyLogger = new DefaultMessageLogger(Message.MSG_DEBUG)

  private def matchVariant(mrid: ModuleRevisionId, variant: Variant): Boolean = {
    val moduleId = mrid.getModuleId()
    variant.attribute(NameAttribute).values == Set(moduleId.getName()) &&
      variant.attribute(OrgAttribute).values == Set(moduleId.getOrganisation()) &&
      variant.attribute(VersionAttribute).values == Set(mrid.getRevision())
  }

  private def matchesExcludeRule(excludeRule: ExcludeRule, variant: Variant): Boolean = {
    val moduleId = excludeRule.getId.getModuleId
    variant.attribute(NameAttribute).values == Set(moduleId.getName()) &&
      variant.attribute(OrgAttribute).values == Set(moduleId.getOrganisation())
  }

  private def getParentNode(resolveReport: ResolveReport) = {
    resolveReport.getDependencies().asScala.map { case i: IvyNode => i }.head //Feels a bit scary?
  }

  private def getExcludeRules(parentNode: IvyNode, ivyNode: IvyNode) = {
    for { //handle nulls
      parentNode <- Option(parentNode).toSet[IvyNode]
      currentIvyNode <- Option(ivyNode).toSet[IvyNode]
      dependencyDescriptor <- Option(currentIvyNode.getDependencyDescriptor(parentNode)).toSet[DependencyDescriptor]
      excludeRule <- {
        if (dependencyDescriptor.getAllIncludeRules().nonEmpty) {
          logger.warn("in: " + parentNode + " there is a dependency:" + currentIvyNode + " which has inlcude rules: " + dependencyDescriptor.getAllIncludeRules().toList + " which are not supported") //TODO: add support
        }
        dependencyDescriptor.getAllExcludeRules()
      }
    } yield {
      excludeRule
    }
  }

  def insert(baseDir: File, results: Set[IvyImportResult], progress: ProgressMonitor): Set[ResolutionResult] = {
    progress.beginTask("Applying exclusion(s)", results.size * 2)
    val included = results.flatMap { result =>
      var requirementModifications = Map.empty[Id, Set[Variant]]
      var currentExcluded = false

      for {
        otherResult <- results
        ((variantId, requirementId), excludeRules) <- result.excludeRules
        excludeRule <- excludeRules
        if (matchesExcludeRule(excludeRule, otherResult.variant))
      } { //<-- NOTICE
        if (variantId == result.variant.id) {
          logger.debug("on variant: " + variantId + " add exclusion for " + requirementId + ":" + excludeRules)
          val formerlyExcluded = requirementModifications.getOrElse(requirementId, Set.empty[Variant])
          requirementModifications += requirementId -> (formerlyExcluded + otherResult.variant) //MUTATE!
        }
        if (requirementId == result.variant.id) {
          logger.debug("on result: " + result.variant.id + " will be excluded because of: " + excludeRules)
          currentExcluded = true //MUTATE!
        }
      }
      val fixedResult = if (requirementModifications.nonEmpty) {
        val fixedRequirements = result.variant.requirements.map { requirement =>
          requirementModifications.get(requirement.id).map { excludedVariants =>
            logger.debug("Excluding: " + excludedVariants.map(_.id) + " on " + requirement.id + " in " + result.variant.id)
            requirement.copy(
              exclusions = requirement.exclusions ++ excludedVariants.map(_.id))
          }.getOrElse(requirement)
        }
        val fixedVariant = result.variant.copy(requirements = fixedRequirements)
        result.copy(variant = fixedVariant)
      } else {
        result
      }

      if (currentExcluded) {
        None
      } else {
        Some(fixedResult)
      }
    }
    progress.endTask()

    val grouped = included.groupBy(_.repository) //grouping to avoid multiple parallel operations on same repo
    progress.beginTask("Writing Ivy results to repo(s)", grouped.size)
    grouped.par.foreach { //NOTICE .par TODO: replace with something more optimized for IO not for CPU
      case (_, results) =>
        results.foreach { result =>
          val variant = result.variant
          val id = variant.id

          val repository = new GitRepository(baseDir, result.repository)
          if (!repository.exists) repository.init()
          val variantMetadata = VariantMetadata.fromVariant(variant)
          //
          //          println("wrote"+id + " in "+repository.name)
          repository.add(variantMetadata.write(id, repository))
          val commit = repository.commit("Ivy Import of " + variant.id)
          repository.add(VersionOrder.useDefaultVersionOrder(id, repository, commit))
          repository.commit("Ordered Ivy Import of " + variant.id)
        }
        progress.update(1)
    }
    progress.endTask()
    progress.beginTask("Converting Ivy version in repo(s)", grouped.size)
    val all = Set() ++ grouped.par.flatMap { //NOTICE .par TODO: same as above (IO vs CPU)
      case (_, results) =>
        val completedResults = results.flatMap { result =>
          val variant = result.variant
          val id = variant.id

          val repository = new GitRepository(baseDir, result.repository)
          if (!repository.exists) repository.init()
          val variantMetadata = VariantMetadata.fromVariant(variant)

          val includedVersionInfo = result.versionInfo

          val currentResults = VersionOrder.createResolutionResults(baseDir, includedVersionInfo) ++
            Set(ResolutionResult(id, repository.name, repository.getHead, variantMetadata.hash))

          val resolutionResultsMetadata = ResolutionResultsMetadata(currentResults.toSeq)
          repository.add(resolutionResultsMetadata.write(id, variantMetadata.hash, repository))
          repository.commit("Resolution results of " + variant.id)
          currentResults
        }
        progress.update(1)
        completedResults
    }
    progress.endTask()

    progress.beginTask("GCing new Ivy repo(s)", grouped.size)
    grouped.par.foreach { //NOTICE .par TODO: same as above (IO vs CPU)
      case (name, _) =>
        val repository = new GitRepository(baseDir, name)
        repository.gc()
        progress.update(1)
    }
    progress.endTask()
    all
  }

  def load(path: Option[String] = None, ivyLogger: AbstractMessageLogger = errorIvyLogger): Ivy = {
    //setting up logging
    Message.setDefaultLogger(ivyLogger)
    val ivy = IvyContext.getContext.getIvy
    val loadedIvy = path.map { path =>
      val ivySettings = new File(path)
      if (!ivySettings.isFile) {
        throw AdeptIvyException(ivySettings + " is not a file")
      } else {
        ivy.configure(ivySettings)
        ivy
      }
    }.getOrElse {
      ivy.configureDefault()
      ivy
    }

    val settings = loadedIvy.getSettings()
    //ivyRoot.foreach(settings.setDefaultIvyUserDir) //FIXME: TODO I do not understand why this does not WORK?!?! Perhaps I didn't well enough?
    loadedIvy.setSettings(settings)
    loadedIvy
  }

  def resolveOptions(confs: String*) = {
    val resolveOptions = new ResolveOptions()
    if (confs.nonEmpty) resolveOptions.setConfs(confs.toArray)
    resolveOptions.setCheckIfChanged(true)
    resolveOptions.setRefresh(true)
    resolveOptions.setDownload(true)
    resolveOptions.setOutputReport(false) //TODO: to true?
    resolveOptions
  }

  val unsupportedStrings = Set("%", "!", "[", "]", "@", "#")

  private def findFallback(confExpr: String): (String, Option[String]) = {
    val FallbackExpr = """(.*?)\((.*?)\)$""".r
    confExpr.trim match {
      case FallbackExpr(rest, fallbackExpr) => rest.trim() -> Some(fallbackExpr.trim())
      case _ => confExpr.trim() -> None
    }
  }

  private def matchConf(name: String, other: String) = {
    other match {
      case "*" => true
      case `name` => true
      case _ => false
    }
  }

  private def getAllConfigurations(module: ModuleDescriptor, confName: String): Set[String] = {
    def getAllConfigurations(module: ModuleDescriptor, existing: Set[String]): Set[String] = {
      existing.flatMap { confName =>
        val newConfs = (module.getConfiguration(confName).getExtends().toSet) + confName
        getAllConfigurations(module, newConfs.diff(existing)) ++ newConfs
      }
    }
    getAllConfigurations(module, Set(confName))
  }

  private def convertDescriptor2Requirements(descriptor: DependencyDescriptor, allConfExprs: Set[String], allIvyImportResults: Set[IvyImportResult]) = {
    var requirements = Set.empty[Requirement]
    allIvyImportResults.foreach { result =>
      if (matchVariant(descriptor.getDependencyRevisionId, result.variant)) {
        val resultConfs = result.variant.attribute(ConfigurationAttribute).values
        allConfExprs.foreach { confExpr =>
          resultConfs.foreach { resultConf =>
            if (matchConf(resultConf, confExpr)) {
              val exclusions = for {
                otherResult <- allIvyImportResults
                excludeRule <- descriptor.getAllExcludeRules()
                if matchesExcludeRule(excludeRule, otherResult.variant)
              } yield {
                otherResult.variant.id
              }
              val newRequirement = Requirement(result.variant.id, Set.empty, exclusions)
              requirements += newRequirement
            }
          }
        }
      }
    }
    requirements
  }

  def ivyRequirements(module: ModuleDescriptor, allIvyImportResults: Set[IvyImportResult]): Map[String, Set[Requirement]] = {
    var requirements = Map.empty[String, Set[Requirement]]

    //pass 1: convert everything to requirements
    module.getDependencies().foreach { descriptor =>
      descriptor.getModuleConfigurations().foreach { confName =>
        descriptor.getDependencyConfigurations(confName).foreach { configurationExpr =>
          val allValidConfigExprs = {
            configurationExpr.split(",").flatMap { possibleFallbackExpr =>
              if (unsupportedStrings.exists(illegal => possibleFallbackExpr.contains(illegal))) throw new Exception("Cannot process configuration: " + configurationExpr + " in " + descriptor + " because it contains part of a string we do not support: " + unsupportedStrings)
              val (rest, fallback) = findFallback(possibleFallbackExpr)
              getAllConfigurations(module, rest) ++ fallback.toSet[String].flatMap(getAllConfigurations(module, _))
            }
          }
          val newRequirements = convertDescriptor2Requirements(descriptor, allValidConfigExprs.toSet, allIvyImportResults)
          val formerRequirements = requirements.getOrElse(confName, Set.empty[Requirement])
          requirements += confName -> (formerRequirements ++ newRequirements)
        }
      }
    }

    //pass 2: expand the requirements of each configuration
    module.getDependencies().foreach { descriptor =>
      descriptor.getModuleConfigurations().foreach { confName =>
        val allRequirements = getAllConfigurations(module, confName).flatMap { expandedConfName =>
          requirements.getOrElse(expandedConfName, Set.empty[Requirement])
        }
        requirements += confName -> allRequirements

      }
    }
    requirements
  }

  def ivyIdAsId(moduleId: ModuleId): Id = {
    Id(moduleId.getName)
  }

  def withConfiguration(id: Id, confName: String): Id = {
    Id(id.value + Id.Sep + IdConfig + Id.Sep + confName)
  }

  def ivyIdAsId(moduleId: ModuleId, confName: String): Id = {
    assert(!confName.contains(Id.Sep))
    withConfiguration(Id(moduleId.getName), confName)
  }

  def ivyIdAsRepositoryName(moduleId: ModuleId): RepositoryName = {
    RepositoryName(moduleId.getOrganisation)
  }

  def ivyIdAsVersion(mrid: ModuleRevisionId): Version = {
    Version(mrid.getRevision)
  }

}

case class IvyImportResult(variant: Variant, artifacts: Set[Artifact], localFiles: Map[ArtifactHash, File], repository: RepositoryName, versionInfo: Set[(RepositoryName, Id, Version)], excludeRules: Map[(Id, Id), Set[ExcludeRule]])

class IvyHelper(ivy: Ivy, changing: Boolean = true, skippableConf: Option[Set[String]] = Some(Set("javadoc", "sources"))) extends Logging {
  import AttributeDefaults.{ NameAttribute, OrgAttribute, VersionAttribute }
  import IvyHelper._

  /** As in sbt */
  private[adept] def cleanModule(mrid: ModuleRevisionId, resolveId: String, manager: ResolutionCacheManager) {
    val files =
      Option(manager.getResolvedIvyFileInCache(mrid)).toList :::
        Option(manager.getResolvedIvyPropertiesInCache(mrid)).toList :::
        Option(manager.getConfigurationResolveReportsInCache(resolveId)).toList.flatten
    import scala.reflect.io.Directory
    files.foreach { file =>
      (new Directory(file)).deleteRecursively() //TODO: I hope this works on files and on directories? Perhaps use something else? 
    }
  }

  def verifyImport(confName: String, module: ModuleDescriptor, resolvedResult: ResolvedResult): Either[IvyVerficationErrorReport, Set[Id]] = {
    val resolvedVariants = resolvedResult.state.resolvedVariants
    val adeptIds = resolvedVariants.keySet
    val allDepArtifacts = resolvedVariants.flatMap {
      case (_, variant) =>
        variant.artifacts.map { artifact =>
          artifact.hash -> variant
        }
    }
    var adeptExtraArtifacts = allDepArtifacts
    var ivyExtraArtifacts = Map.empty[ArtifactHash, ModuleRevisionId]
    var nonMatchingArtifacts = Set.empty[(ArtifactHash, Variant, Set[ArtifactHash])]

    importAsSbt(module, resolveOptions(confName)) match {
      case Right(resolveReport) =>
        val configurationReport = resolveReport.getConfigurationReport(confName)
        configurationReport.getAllArtifactsReports().foreach { artifactReport =>
          val ivyArtifact = artifactReport.getArtifact()

          val ivyArtifactHash = {
            val fis = new FileInputStream(artifactReport.getLocalFile())
            try {
              ArtifactHash(Hasher.hash(fis))
            } finally {
              fis.close()
            }
          }
          val mrid = ivyArtifact.getModuleRevisionId()
          val targetId = ivyIdAsId(mrid.getModuleId, configurationReport.getConfiguration)
          adeptExtraArtifacts -= ivyArtifactHash //we found an artifact in ivy which we was found in adept
          resolvedVariants.get(targetId) match {
            case Some(variant) =>
              val matchingArtifacts = variant.artifacts.filter { artifact =>
                artifact.attribute(ArtifactConfAttribute).values == ivyArtifact.getConfigurations()
              }

              //we did not find 1 artifact matching or some of the hashes are different
              if (matchingArtifacts.size != 1 || ivyArtifactHash != matchingArtifacts.head.hash) {
                nonMatchingArtifacts += ((ivyArtifactHash, variant, matchingArtifacts.map(_.hash)))
              }
            case None => {
              if (!allDepArtifacts.isDefinedAt(ivyArtifactHash)) {
                ivyExtraArtifacts += ((ivyArtifactHash, mrid)) //we found an artifact 
              }
            }
          }
        }

        if (nonMatchingArtifacts.isEmpty && ivyExtraArtifacts.isEmpty && adeptExtraArtifacts.isEmpty) {
          Right(adeptIds)
        } else {
          Left(IvyVerficationErrorReport(
            msg = "Ivy was resolved, but there was mis-matching artifacts found",
            adeptExtraArtifacts,
            ivyExtraArtifacts,
            nonMatchingArtifacts))
        }
      case Left(error) =>
        Left(IvyVerficationErrorReport(
          msg = error,
          adeptExtraArtifacts,
          ivyExtraArtifacts,
          nonMatchingArtifacts))
    }
  }

  private def importAsSbt(module: ModuleDescriptor, initialResolveOption: ResolveOptions) = {
    //    val currentResolveOptions = resolveOptions()
    //    val resolveId = ResolveOptions.getDefaultResolveId(module)
    //    currentResolveOptions.setResolveId(resolveId)
    //    cleanModule(module.getModuleRevisionId, resolveId, ivy.getSettings.getResolutionCacheManager)

    def reportErrorString(resolveReport: ResolveReport) = {
      val messages = resolveReport.getAllProblemMessages.toArray.map(_.toString).distinct
      val failed = resolveReport.getUnresolvedDependencies
      failed.mkString(",") + "failed to resolve. Messages:\n" + messages.mkString("\n")
    }

    val resolveReport = ivy.resolve(module, resolveOptions())

    if (resolveReport.hasError) {
      Left("Got errors when trying to resolve from Ivy: " + reportErrorString(resolveReport))
    } else {
      Right(resolveReport)
    }
  }

  /**
   * Import Ivy Module
   */
  def ivyImport(module: ModuleDescriptor, progress: ProgressMonitor): Set[IvyImportResult] = { //, Set[ResolutionResult]) = {
    ivy.synchronized { //ivy is not thread safe
      val mrid = module.getModuleRevisionId()
      progress.beginTask("Resolving Ivy module(s)", module.getDependencies().size)
      importAsSbt(module, resolveOptions()) match {
        case Right(resolveReport) =>
          progress.update(module.getDependencies().size)
          progress.endTask()
          val dependencyTree = createDependencyTree(mrid)(resolveReport)
          progress.start(module.getDependencies().size)
          val mergableResults = module.getDependencies().flatMap { directDependency =>
            val drid = directDependency.getDependencyRevisionId()
            ivySingleImport(drid.getOrganisation(), drid.getName(), drid.getRevision(), progress)
          }
          mergableResults.toSet
        case Left(error) => throw new Exception(error)
      }
    }
  }

  private def ivySingleImport(org: String, name: String, version: String, progress: ProgressMonitor): Set[IvyImportResult] = {
    val mrid = ModuleRevisionId.newInstance(org, name, version)
    val resolveReport = ivy.resolve(mrid, resolveOptions(), changing)

    println("--------------")
    val dependencyTree = createDependencyTree(mrid)(resolveReport)
    val workingNode = dependencyTree(ModuleRevisionId.newInstance(org, name + "-caller", "working")).head
    progress.beginTask("Importing " + mrid, dependencyTree(workingNode.getId).size)
    val mergableResults = results(workingNode, progress, progressIndicatorRoot = true)(dependencyTree)
    progress.endTask()
    mergableResults
  }

  private def results(currentIvyNode: IvyNode, progress: ProgressMonitor, progressIndicatorRoot: Boolean)(dependencies: Map[ModuleRevisionId, Set[IvyNode]]): Set[IvyImportResult] = {
    val mrid = currentIvyNode.getId
    val children = dependencies.getOrElse(mrid, Set.empty)

    val currentResults = createIvyResult(currentIvyNode, children, dependencies)
    val allResults = children.flatMap { childNode =>
      val childId = childNode.getId
      val dependencyTree = createDependencyTree(childId)(ivy.resolve(childId, resolveOptions(), changing))
      val finished = results(childNode, progress, progressIndicatorRoot = false)(dependencies ++ dependencyTree)
      if (progressIndicatorRoot) progress.update(1)
      finished
    } ++ currentResults
    allResults
  }

  private def createIvyResult(currentIvyNode: IvyNode, unloadedChildren: Set[IvyNode], dependencies: Map[ModuleRevisionId, Set[IvyNode]]): Set[IvyImportResult] = {
    val mrid = currentIvyNode.getId
    val id = ivyIdAsId(mrid.getModuleId)
    val versionAttribute = Attribute(VersionAttribute, Set(mrid.getRevision()))
    val nameAttribute = Attribute(NameAttribute, Set(mrid.getName()))
    val orgAttribute = Attribute(OrgAttribute, Set(mrid.getOrganisation()))

    val configurationHash = Hasher.hash(mrid.toString.getBytes) //TODO: make more unique? 
    val attributes = Set(orgAttribute, nameAttribute, versionAttribute)

    val dependencyReport = ivy.resolve(mrid, resolveOptions(), changing)
    val moduleDescriptor = dependencyReport.getModuleDescriptor()
    val parentNode = getParentNode(dependencyReport)
    val mergableResults = dependencyReport.getConfigurations()
      .map(c => parentNode.getConfiguration(c)) //careful here. you could think: moduleDescriptor.getConfigurations is the same but it is not (you get bogus configurations back) 
      .filter(_.getVisibility() == Visibility.PUBLIC) //we cannot get dependencies for private configurations so we just skip them all together
      .map { ivyConfiguration =>
        val confName = ivyConfiguration.getName
        val thisVariantId = ivyIdAsId(mrid.getModuleId, confName)

        val (loaded, notLoaded) = {
          val rootConf = confName //TODO: I am honestly not a 100% sure how rootConf is different from actual conf?
          val children = parentNode.getDependencies(rootConf, confName, "*").asScala.flatMap { //we cannot use unloadedChildren directly, because they might not be loaded (if they are provided/evicted)
            case ivyNode: IvyNode =>
              unloadedChildren.find { child =>
                child.getId == ivyNode.getId
              }
          }.toSet
          children.partition(_.isLoaded)
        }

        //print warnings:
        notLoaded.foreach { ivyNode => //TODO: I am not a 100% certain that we do not really need them? Where do these deps come from, somebody wanted them originally?
          if (!dependencies.isDefinedAt(ivyNode.getId)) {
            logger.debug(mrid + " has a node " + ivyNode + " which was not loaded, but it is not required in upper-call tree so we ignore")

            if (ivyNode == null) {
              logger.error("Got a null while loading: " + mrid)
            } else if (ivyNode.isEvicted(confName))
              logger.debug(mrid + " evicts " + ivyNode + " so it was not loaded.")
            else if (ivyNode.getDescriptor() != null && ivyNode.getDescriptor().canExclude()) {
              logger.debug(mrid + " required" + ivyNode + " which can be excluded.")
            } else {
              logger.error(mrid + " required " + ivyNode + ", but is was not loaded (nor evicted) so cannot import. This is potentially a problem") //TODO: is this acceptable? if not find a way to load ivy nodes...
            }
          } else throw new Exception("Could not load " + ivyNode + "declared in: " + mrid)
        }
        //exclude rules:
        var excludeRules = Map.empty[(Id, Id), Set[ExcludeRule]]

        //requirements:
        val requirements = loaded.flatMap { ivyNode =>
          val currentExcludeRules = getExcludeRules(currentIvyNode, ivyNode)
          if (!ivyNode.isEvicted(confName)) {
            val requirements = ivyNode.getConfigurations(confName).toSet.map(ivyNode.getConfiguration).map { requirementConf =>
              Requirement(ivyIdAsId(ivyNode.getId.getModuleId, requirementConf.getName()), Set.empty, Set.empty)
            } + Requirement(ivyIdAsId(ivyNode.getId.getModuleId), Set.empty, Set.empty)
            requirements.foreach { requirement =>
              if (currentExcludeRules.nonEmpty) {
                excludeRules += (thisVariantId, requirement.id) -> currentExcludeRules //<-- MUTATE!
              }
            }
            requirements
          } else Set.empty[Requirement]
        }

        val artifactInfos = ivy.resolve(mrid, resolveOptions(ivyConfiguration.getName), changing).getArtifactsReports(mrid).flatMap { artifactReport =>
          if (artifactReport.getArtifact().getConfigurations().toList.contains(confName)) {
            val file = artifactReport.getLocalFile
            if (file != null) {
              val hash = {
                val is = new FileInputStream(file)
                try {
                  ArtifactHash(Hasher.hash(is))
                } finally {
                  is.close()
                }
              }
              Some((artifactReport.getArtifactOrigin().getLocation(), artifactReport.getArtifact().getConfigurations(), file, hash, file.getName))
            } else if (file == null && skippableConf.isDefined && skippableConf.get(ivyConfiguration.getName())) {
              None
            } else {
              throw new Exception("Could not download: " + mrid + " in " + confName)
            }
          } else None
        }.toSet

        //TODO: skipping empty configurations? if (artifactInfos.nonEmpty || dependencies.nonEmpty)... 
        val artifacts = artifactInfos.map {
          case (location, _, file, hash, filename) =>
            Artifact(hash, file.length, Set(location))
        }

        val artifactRefs = artifactInfos.map {
          case (_, ivyConfs, file, hash, filename) =>
            ArtifactRef(hash, Set(ArtifactAttribute(ArtifactConfAttribute, ivyConfs.toSet)), Some(filename))
        }

        val localFiles = artifactInfos.map {
          case (_, _, file, hash, _) =>
            hash -> file
        }.toMap

        val configurationRequirements = ivyConfiguration.getExtends().map { targetConf =>
          Requirement(ivyIdAsId(mrid.getModuleId, targetConf), Set(Constraint(ConfigurationHashAttribute, Set(configurationHash))), Set.empty)
        }

        val variant = Variant(
          id = thisVariantId,
          attributes = attributes + Attribute(ConfigurationHashAttribute, Set(configurationHash)) + Attribute(ConfigurationAttribute, Set(confName)),
          artifacts = artifactRefs,
          requirements = requirements ++ configurationRequirements)

        val targetVersionInfo: Set[(RepositoryName, Id, Version)] = loaded.flatMap { ivyNode =>
          if (!ivyNode.isEvicted(confName)) {
            val targetRepositoryName = ivyIdAsRepositoryName(ivyNode.getId.getModuleId)
            val targetVersion = ivyIdAsVersion(ivyNode.getId)
            ivyNode.getConfigurations(confName).toSet.map(ivyNode.getConfiguration).map { requirementConf =>
              val targetId = ivyIdAsId(ivyNode.getId.getModuleId, requirementConf.getName)
              (targetRepositoryName, targetId, targetVersion)
            } + ((targetRepositoryName, ivyIdAsId(ivyNode.getId.getModuleId), targetVersion))
          } else {
            Set.empty[(RepositoryName, Id, Version)]
          }
        }

        IvyImportResult(
          variant = variant,
          artifacts = artifacts,
          localFiles = localFiles,
          repository = ivyIdAsRepositoryName(mrid.getModuleId),
          versionInfo = targetVersionInfo,
          excludeRules = excludeRules)
      }.toSet

    mergableResults +
      IvyImportResult( //<-- adding main configuration to make sure that there is not 2 variants with different "configurations" 
        variant = Variant(id, attributes = attributes + Attribute(ConfigurationHashAttribute, Set(configurationHash))),
        artifacts = Set.empty,
        localFiles = Map.empty,
        repository = ivyIdAsRepositoryName(mrid.getModuleId),
        versionInfo = Set.empty,
        excludeRules = Map.empty)
  }

  private def createDependencyTree(mrid: ModuleRevisionId)(report: ResolveReport) = { //TODO: rename to requirement? or perhaps not?
    var dependencies = Map.empty[ModuleRevisionId, Set[IvyNode]]
    def addDependency(mrid: ModuleRevisionId, ivyNode: IvyNode) = {
      val current = dependencies.getOrElse(mrid, Set.empty) + ivyNode
      dependencies += mrid -> current
    }

    report.getDependencies().asScala.foreach {
      case ivyNode: IvyNode =>
        if (mrid != ivyNode.getId) addDependency(mrid, ivyNode)
    }

    val currentCallers = report.getDependencies().asScala.foreach {
      case ivyNode: IvyNode => ivyNode.getAllCallers.map { caller =>
        if (caller.getModuleRevisionId != ivyNode.getId) addDependency(caller.getModuleRevisionId, ivyNode)
      }
    }
    dependencies
  }
}