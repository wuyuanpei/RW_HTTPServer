import java.io.*;
import java.util.*;

/**
 * A thread for the command
 */
public class CommandThread implements Runnable {

	public static final String CMDS[] = { "help", "shutdown" };
	public Vector<Command> commandQ;

	public CommandThread() {
		commandQ = new Vector<>();
	}

	@Override
	public void run() {
		try {
			BufferedReader br = new BufferedReader(new InputStreamReader(System.in));

			while (true) {
				System.out.print("command>");
				String command = br.readLine();

				if (CMDS[0].equals(command)) {
					help();
				} else if (CMDS[1].equals(command)) {
					shutdown();
					return;
				} else {
					System.out.println("command not found. Type \"help\" for help");
				}
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public void help() {
		System.out.println("commands:");
		System.out.println("\thelp      : print out this message");
		System.out.println("\tshutdown  : shutdown the selector");
	}

	public void shutdown() {
		System.out.println("Shutting down the server...");
		synchronized(commandQ){
			commandQ.add(new ShutdownCommand());
		}
		SelectHTTPServer.selector.wakeup();
	}
}
