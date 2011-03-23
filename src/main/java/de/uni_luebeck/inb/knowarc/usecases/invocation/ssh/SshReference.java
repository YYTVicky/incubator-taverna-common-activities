/**
 * 
 */
package de.uni_luebeck.inb.knowarc.usecases.invocation.ssh;

import java.io.InputStream;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.SftpException;

import net.sf.taverna.t2.activities.externaltool.RetrieveLoginFromTaverna;
import net.sf.taverna.t2.reference.AbstractExternalReference;
import net.sf.taverna.t2.reference.DereferenceException;
import net.sf.taverna.t2.reference.ExternalReferenceSPI;
import net.sf.taverna.t2.reference.ReferenceContext;

/**
 * @author alanrw
 *
 */
public class SshReference extends AbstractExternalReference implements
	ExternalReferenceSPI {
	
	private String host = "127.0.0.1";
	private int port = 22;
	private String directory = "/tmp/";
	private String subDirectory;
	private String fileName;
	
	public SshReference() {
		super();
		this.setReferencingMutableData(true);
		this.setReferencingDeletableData(true);
	}
	
	public SshReference(SshUrl url) {
		super();
		this.setReferencingMutableData(true);
		this.setReferencingDeletableData(true);
		this.host = url.getSshNode().getHost();
		this.port = url.getSshNode().getPort();
		this.directory = url.getSshNode().getDirectory();
		this.subDirectory = url.getSubDirectory();
		this.fileName = url.getFileName();
	}

	/* (non-Javadoc)
	 * @see net.sf.taverna.t2.reference.ExternalReferenceSPI#getApproximateSizeInBytes()
	 */
	@Override
	public Long getApproximateSizeInBytes() {
		return 10000L;
	}

	/* (non-Javadoc)
	 * @see net.sf.taverna.t2.reference.ExternalReferenceSPI#openStream(net.sf.taverna.t2.reference.ReferenceContext)
	 */
	@Override
	public InputStream openStream(ReferenceContext context)
			throws DereferenceException {
		try {
			SshNode node = new SshNode();
			node.setHost(this.getHost());
			node.setPort(this.getPort());
			node.setDirectory(this.getDirectory());
			String fullPath = getDirectory() +  getSubDirectory() + "/" + getFileName();
			ChannelSftp channel = SshPool.getSftpGetChannel(node, new RetrieveLoginFromTaverna(new SshUrl(node).toString()));
			System.err.println("Opening stream on " + fullPath);
			return (channel.get(fullPath));
		} catch (JSchException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (SftpException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return null;
	}

	/**
	 * @return the host
	 */
	public String getHost() {
		return host;
	}

	/**
	 * @param host the host to set
	 */
	public void setHost(String host) {
		this.host = host;
	}

	/**
	 * @return the port
	 */
	public int getPort() {
		return port;
	}

	/**
	 * @param port the port to set
	 */
	public void setPort(int port) {
		this.port = port;
	}

	/**
	 * @return the directory
	 */
	public String getDirectory() {
		return directory;
	}

	/**
	 * @param directory the directory to set
	 */
	public void setDirectory(String directory) {
		this.directory = directory;
	}

	/**
	 * @return the subDirectory
	 */
	public String getSubDirectory() {
		return subDirectory;
	}

	/**
	 * @param subDirectory the subDirectory to set
	 */
	public void setSubDirectory(String subDirectory) {
		this.subDirectory = subDirectory;
	}

	/**
	 * @return the fileName
	 */
	public String getFileName() {
		return fileName;
	}

	/**
	 * @param fileName the fileName to set
	 */
	public void setFileName(String fileName) {
		this.fileName = fileName;
	}

}