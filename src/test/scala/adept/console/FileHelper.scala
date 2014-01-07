package adept.console
import java.io.File

class FileHelper(file : File) {
  def deleteAll : Unit = {
    def deleteFile(dfile : File) : Unit = {
      if(dfile.isDirectory)
        dfile.listFiles.foreach{ f => deleteFile(f) }
      dfile.delete
    }
    deleteFile(file)
  }
}

object FileHelper{
  implicit def file2helper(file : File) = new FileHelper(file)
}