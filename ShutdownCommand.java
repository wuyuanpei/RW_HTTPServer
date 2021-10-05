import java.util.*;
import java.io.IOException;
import java.nio.*;
import java.nio.channels.*;

/**
 * The command of shutting down the server
 * The command will disable ServerSocketChannel 
 * Every time the event loop handles some events, at the end, if "stop" is set, 
 * it will check wither SelectHTTPServer.selector.keys() still has open channles, 
 * if not, close the selector.
 * Therefore, after running this command, the server will shut down shortly
 */
public class ShutdownCommand extends Command{
	@Override
	public void runCommand() {
		Set<SelectionKey> keys = SelectHTTPServer.selector.keys();
		Iterator<SelectionKey> it = keys.iterator();
		while(it.hasNext()){
			SelectionKey key = it.next();
			// only shutdown the ServerSocketChannel
			if(key.channel() instanceof ServerSocketChannel){
				try{
					key.channel().close();
					key.cancel();
				} catch(IOException e){
					e.printStackTrace();
					System.out.println("shutdown command fail!");
					return;
				}
			}
		}
		SelectHTTPServer.stop = true;
	}
}
