package adept.ivy

import org.apache.ivy.core.module.id.ModuleRevisionId
import adept.repository.models.ConfiguredVariantsMetadata
import adept.models.Artifact
import java.io.File
import adept.ext.conversions.Conversion

case class IvyImportResult(mrid: ModuleRevisionId, variantsMetadata: ConfiguredVariantsMetadata, artifacts: Set[Artifact], localFiles: Map[Artifact, File]) {
 def convertWith(conversion: Conversion) = {
   conversion.convert(variantsMetadata).map { oldMetadata =>
     this.copy( variantsMetadata = oldMetadata )
   }
 }
}