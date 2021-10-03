public class Util {

	public static final boolean _DEBUG = true;

	/**
	 * Panic with message printed out and exit with status
	 * @param status
	 * @param message
	 */
	public static void panic(int status, String message){
		System.out.println(message);
		System.exit(status);
	}

	public static void DEBUG(String s) {
		if (_DEBUG)
			System.out.println(s);
	}
}
