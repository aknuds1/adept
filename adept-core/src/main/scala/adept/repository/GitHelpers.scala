package adept.repository

import org.eclipse.jgit.transport.JschConfigSessionFactory
import org.eclipse.jgit.transport.OpenSshConfig
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.CredentialItem
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.CredentialsProviderUserInfo
import org.eclipse.jgit.transport.SshSessionFactory
import adept.repository.models.Commit
import adept.logging.Logging

private[adept] object GitHelpers extends Logging {
  def lastestCommits(repository: GitRepository, commits: Set[Commit]): Set[Commit] = {
    if (commits.isEmpty) Set.empty
    else {
      val latest = commits.tail.foldLeft(commits) { (allCurrentLatest, current) =>
        allCurrentLatest.flatMap { currentLatest =>
          repository.compareCommits(currentLatest, current) match {
            case (Some(first), Some(second)) => Set(first)
            case commits =>
              allCurrentLatest + current
          }
        }
      }
      latest
    }
  }

  /**
   *  Use ssh git credentials
   *
   *  Not thread-safe, therefore synchronized
   */
  def withGitSshCredentials[A](passphrase: Option[String])(f: => A): A = synchronized {
    val sessionFactory = new JschConfigSessionFactory {
      def configure(hc: OpenSshConfig.Host, session: com.jcraft.jsch.Session): Unit = {
        val provider = new CredentialsProvider() {
          override def isInteractive = false
          override def supports(items: CredentialItem*) = true
          override def get(uri: URIish, items: CredentialItem*) = {
            passphrase match {
              case Some(passphrase) => //set passphrase
                items.foreach { case item: CredentialItem.StringType => item.setValue(passphrase) }
              case None => //do nothing
            }
            true
          }
        }

        val userInfo = new CredentialsProviderUserInfo(session, provider)
        session.setUserInfo(userInfo)
      }
    }
    val prev = SshSessionFactory.getInstance()
    try {
      SshSessionFactory.setInstance(sessionFactory)
      f
    } finally {
      SshSessionFactory.setInstance(prev)
    }
  }
}