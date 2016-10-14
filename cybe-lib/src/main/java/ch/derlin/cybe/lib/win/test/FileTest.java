package ch.derlin.cybe.lib.win.test;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinBase.FILETIME;
import com.sun.jna.platform.win32.WinNT.HANDLE;

import ch.derlin.cybe.lib.win.test.Kernel32.BY_HANDLE_FILE_INFORMATION;

public class FileTest {

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		//test("\\\\VBOXSVR\\home\\git\\cybe-java\\out\\test\\cybe-java\\basic\\.cybe");
		test("C:\\Users\\lucy\\Desktop\\test.pl");
	}

	public static void test(String path) {
		// http://msdn.microsoft.com/en-us/library/windows/desktop/aa363858%28v=vs.85%29.aspx
		final int FILE_SHARE_READ = (0x00000001);
		// final int FILE_SHARE_WRITE = (0x00000002);
		// final int FILE_SHARE_DELETE = (0x00000004);
		final int OPEN_EXISTING = (3);
		final int GENERIC_READ = (0x80000000);
		// final int GENERIC_WRITE = (0x40000000);
		// final int FILE_FLAG_NO_BUFFERING = (0x20000000);
		// final int FILE_FLAG_WRITE_THROUGH = (0x80000000);
		// final int FILE_READ_ATTRIBUTES = (0x0080);
		// final int FILE_WRITE_ATTRIBUTES = (0x0100);
		// final int ERROR_INSUFFICIENT_BUFFER = (122);
		final int FILE_ATTRIBUTE_ARCHIVE = (0x20);

		WinBase.SECURITY_ATTRIBUTES attr = null;
		BY_HANDLE_FILE_INFORMATION lpFileInformation = new BY_HANDLE_FILE_INFORMATION();
		HANDLE hFile = null;

		hFile = Kernel32.INSTANCE.CreateFile(path, GENERIC_READ,
				FILE_SHARE_READ, attr, OPEN_EXISTING, FILE_ATTRIBUTE_ARCHIVE,
				null);

		System.out.println("CreateFile last error:"
				+ Kernel32.INSTANCE.GetLastError());

		// if (hFile. != -1)
		{

			ch.derlin.cybe.lib.win.test.Kernel32.INSTANCE.GetFileInformationByHandle(hFile,
					lpFileInformation);

			System.out.println("CREATION TIME: "
					+ FILETIME.filetimeToDate(
							lpFileInformation.ftCreationTime.dwHighDateTime,
							lpFileInformation.ftCreationTime.dwLowDateTime));

			System.out.println("VOLUME SERIAL NO.: "
					+ lpFileInformation.dwVolumeSerialNumber);

			System.out.println("FILE INDEX HIGH: "
					+ lpFileInformation.nFileIndexHigh);
			System.out.println("FILE INDEX LOW: "
					+ lpFileInformation.nFileIndexLow);

			System.out.println("GetFileInformationByHandle last error:"
					+ Kernel32.INSTANCE.GetLastError());
		}

		Kernel32.INSTANCE.CloseHandle(hFile);

		System.out.println("CloseHandle last error:"
				+ Kernel32.INSTANCE.GetLastError());

	}

}