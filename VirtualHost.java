/**
 * A virtual host
 */
public class VirtualHost {
	private String docRoot;
	private String serverName;

	public VirtualHost(){}

	public VirtualHost(String docRoot, String serverName){
		this.docRoot = docRoot;
		this.serverName = serverName;
	}

	public String getDocRoot(){
		return docRoot;
	}

	public String getServerName(){
		return serverName;
	}

	public void setDocRoot(String docRoot){
		this.docRoot = docRoot;
	}

	public void setServerName(String serverName){
		this.serverName = serverName;
	}

	public String toString(){
		return "<" + serverName + ", " + docRoot + ">";
	}
}
