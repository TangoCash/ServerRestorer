package me.TedLogan.tbar;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPSClient;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.*;
import java.net.SocketException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import java.util.Calendar;

public class Main extends JavaPlugin {

	private static List<String> exceptions = new ArrayList<String>();
	private static String prefix = "&6[&3TedsBackupAndRestore&6]&8";
	private static String kickmessage = " Restoring server to previous save. Please rejoin in a few seconds.";
	BukkitTask br = null;
	private boolean saveTheConfig = false;
	private File master = null;
	private File backups = null;
	private boolean currentlySaving = false;
	private boolean automate = true;
	private long lastSave = 0;
	private boolean useFTP = false;
	private boolean useFTPS = false;
	private boolean useSFTP = false;
	private String serverFTP = "www.example.com";
	private String userFTP = "User";
	private String passwordFTP = "password";
	private int portFTP = 80;
	private String naming_format = "Backup-%date%";
	private SimpleDateFormat dateformat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
	private String removeFilePath = "";
	private long maxSaveSize = -1;
	private int maxSaveFiles = 1000;
	private boolean deleteZipOnFail = false;
	private boolean deleteZipOnFTP = false;
	private String separator = File.separator;
	private String w_world = "world";
	private String w_nether = "world_nether";
	private String w_end = "world_the_end";
	private List<String> a_days;
	private List<String> a_times;
	private int compression = Deflater.BEST_COMPRESSION;

	public static File newFile(File destinationDir, ZipEntry zipEntry) throws IOException {
		File destFile = new File(destinationDir, zipEntry.getName());

		String destDirPath = destinationDir.getCanonicalPath();
		String destFilePath = destFile.getCanonicalPath();

		if (!destFilePath.startsWith(destDirPath + File.separator)) {
			throw new IOException("Entry is outside of the target dir: " + zipEntry.getName());
		}

		return destFile;
	}

	private static boolean isExempt(String path) {
		path = path.toLowerCase().trim();
		for (String s : exceptions)
			if (path.endsWith(s.toLowerCase().trim()))
				return true;
		return false;
	}

	public static String humanReadableByteCount(long bytes, boolean si) {
		int unit = si ? 1000 : 1024;
		if (bytes < unit)
			return bytes + " B";
		int exp = (int) (Math.log(bytes) / Math.log(unit));
		String pre = (si ? "kMGTPE" : "KMGTPE").charAt(exp - 1) + (si ? "" : "i");
		return String.format("%.1f %sB", bytes / Math.pow(unit, exp), pre);
	}

	public static long folderSize(File directory) {
		long length = 0;
		if(directory==null)return -1;

		for (File file : directory.listFiles()) {
			if (file.isFile())
				length += file.length();
			else
				length += folderSize(file);
		}
		return length;
	}

	public static File firstFileModified(File dir) {
		File fl = dir;
		File[] files = fl.listFiles(new FileFilter() {
			public boolean accept(File file) {
				return file.isFile();
			}
		});
		long lastMod = Long.MAX_VALUE;
		File choice = null;
		for (File file : files) {
			if (file.lastModified() < lastMod) {
				choice = file;
				lastMod = file.lastModified();
			}
		}
		return choice;
	}

	public File getMasterFolder() {
		return master;
	}

	public File getBackupFolder() {
		return backups;
	}

	public long a(String path, long def) {
		if (getConfig().contains(path))
			return getConfig().getLong(path);
		saveTheConfig = true;
		getConfig().set(path, def);
		return def;
	}
	public Object a(String path, Object def) {
		if (getConfig().contains(path))
			return getConfig().get(path);
		saveTheConfig = true;
		getConfig().set(path, def);
		return def;
	}

	@SuppressWarnings("unchecked")
	@Override
	public void onEnable() {
		final List<String> days = new ArrayList<String>();
		days.add("MONDAY");
		days.add("TUESDAY");
		days.add("WEDNESDAY");
		days.add("THURSDAY");
		days.add("FRIDAY");
		days.add("SATURDAY");
		days.add("SUNDAY");
		final List<String> times = new ArrayList<String>();
		times.add("00-00");
		times.add("06-00");
		times.add("12-00");
		times.add("18-00");
		a_days = (List<String>) a("days", days);
		a_times = (List<String>) a("times", times);
		lastSave = a("LastAutosave", 0L);
		master = getDataFolder().getAbsoluteFile().getParentFile().getParentFile();
		List<World> worlds = Bukkit.getWorlds();
		w_world = worlds.get(0).getName();
		w_nether = worlds.get(1).getName();
		w_end = worlds.get(2).getName();
		String path = ((String) a("getBackupFileDirectory", ""));
		backups = new File((path.isEmpty() ? master.getPath() : path) +  File.separator+"backups"+ File.separator);
		if (!backups.exists())
			backups.mkdirs();

		automate = (boolean) a("enableAutoBackup", true);

		naming_format = (String) a("FileNameFormat", naming_format);

		String unPrefix = (String) a("prefix", "&6[&3TedsBackupAndRestore&6]&8");
		prefix = ChatColor.translateAlternateColorCodes('&', unPrefix);
		String kicky = (String) a("kickMessage", unPrefix + " Restoring server to previous backup. Please rejoin in a minute.");
		kickmessage = ChatColor.translateAlternateColorCodes('&', kicky);

		useFTP = (boolean) a("EnableFTP", false);
		useFTPS = (boolean) a("EnableFTPS", false);
		useSFTP = (boolean) a("EnableSFTP", false);
		serverFTP = (String) a("FTPAdress", serverFTP);
		portFTP = (int) a("FTPPort", portFTP);
		userFTP = (String) a("FTPUsername", userFTP);
		passwordFTP = (String) a("FTPPassword", passwordFTP);


		compression = (int) a("CompressionLevel_Max_9", compression);

		removeFilePath = (String) a("FTP_Directory", removeFilePath);

		maxSaveSize = toByteSize((String) a("MaxSaveSize", "300G"));
		maxSaveFiles = (int) a("MaxFileSaved", 168);

		deleteZipOnFTP = (boolean) a("DeleteZipOnFTPTransfer", false);
		deleteZipOnFail = (boolean) a("DeleteZipIfFailed", false);
		separator = (String) a("FolderSeparator", separator);
		if (saveTheConfig)
			saveConfig();
		if (true) {
			final JavaPlugin thi = this;
			br = new BukkitRunnable() {
				@Override
				public void run() {
					Calendar cal = Calendar.getInstance();
					final boolean isBackupDay = a_days.stream().filter(d -> d.equalsIgnoreCase(getDayName(cal.get(7)))).findFirst().isPresent();
					if (isBackupDay && automate) {
						//Bukkit.getConsoleSender().sendMessage(prefix + "is backup day");
						for (final String time : a_times) {
							try {
								final String[] timeStr = time.split("-");
								if (timeStr[0].startsWith("0")) {
									timeStr[0] = timeStr[0].substring(1);
								}
								if (timeStr[1].startsWith("0")) {
									timeStr[1] = timeStr[1].substring(1);
								}
								final int hour = Integer.valueOf(timeStr[0]);
								final int minute = Integer.valueOf(timeStr[1]);
								//Bukkit.getConsoleSender().sendMessage(prefix + String.valueOf(cal.get(12))+" -- "+minute);
								if (cal.get(11) != hour || cal.get(12) != minute) {
									continue;
								}
								new BukkitRunnable() {
									@Override
									public void run() {
										getConfig().set("LastAutosave", lastSave = (System.currentTimeMillis()-5000));
										backup(Bukkit.getConsoleSender());
										saveConfig();
									}
								}.runTaskLater(thi, 0);
								return;
							}
							catch (Exception e) {
								Bukkit.getConsoleSender().sendMessage(prefix + "Automatic Backup failed. Please check that you set the Backup Times correctly.");
							}
						}
					}
				}
			}.runTaskTimerAsynchronously(this, 20, 20*60);
		}

		new Metrics(this);

	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {

		if (args.length == 1) {
			List<String> list = new ArrayList<>();
			String[] commands = new String[]{"disableAutoBackup", "enableAutoBackup", "restore", "backup", "stop"};
			for (String f : commands) {
				if (f.toLowerCase().startsWith(args[0].toLowerCase()))
					list.add(f);
			}
			return list;

		}

		if (args.length > 1) {
			if (args[0].equalsIgnoreCase("restore")) {
				List<String> list = new ArrayList<>();
				for (File f : getBackupFolder().listFiles()) {
					if (f.getName().toLowerCase().startsWith(args[1].toLowerCase()))
						list.add(f.getName());
				}
				return list;
			}
		}
		return super.onTabComplete(sender, command, alias, args);
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (!sender.hasPermission("tedsbackupandrestore.command")) {
			sender.sendMessage(prefix + ChatColor.RED + " You do not have permission to use this command.");
			return true;
		}
		if (args.length == 0) {
			sender.sendMessage(ChatColor.GOLD + "---===+Ted's Backup & Restore+===---");
			sender.sendMessage("/tbar backup : Backup the server");
			sender.sendMessage("/tbar stop : Stops creating a backup of the server");
			sender.sendMessage("/tbar restore <backup> : Restores server to previous backup (automatically restarts)");
			sender.sendMessage("/tbar enableAutoBackup : Enable the autobackup");
			sender.sendMessage("/tbar disableAutoBackup : Disables the autobackup");
			return true;
		}
		if (args[0].equalsIgnoreCase("restore")) {
			if (!sender.hasPermission("tedsbackupandrestore.restore")) {
				sender.sendMessage(prefix + ChatColor.RED + " You do not have permission to use this command.");
				return true;
			}
			if (currentlySaving) {
				sender.sendMessage(prefix + " The server is currently being saved. Please wait.");
				return true;
			}
			if (args.length < 2) {
				sender.sendMessage(prefix + " A valid backup file is required.");
				return true;
			}
			File backup = new File(getBackupFolder(), args[1]);
			if (!backup.exists()) {
				sender.sendMessage(prefix + " The file \"" + args[1] + "\" does not exist.");
				return true;
			}
			restore(backup);
			sender.sendMessage(prefix + " Restoration complete.");
			return true;
		}

		if (args[0].equalsIgnoreCase("stop")) {
			if (!sender.hasPermission("tedsbackupandrestore.save")) {
				sender.sendMessage(prefix + ChatColor.RED + " You do not have permission to use this command.");
				return true;
			}
			if (currentlySaving) {
				currentlySaving=false;
				return true;
			}
			sender.sendMessage(prefix + " The server is not currently being saved.");
			return true;
		}
		if (args[0].equalsIgnoreCase("backup")) {
			if (!sender.hasPermission("tedsbackupandrestore.save")) {
				sender.sendMessage(prefix + ChatColor.RED + " You do not have permission to use this command.");
				return true;
			}
			if (currentlySaving) {
				sender.sendMessage(prefix + " The server is currently being saved. Please wait.");
				return true;
			}
			backup(sender);
			return true;
		}
		if (args[0].equalsIgnoreCase("disableAutoBackup")) {
			if (!sender.hasPermission("tedsbackupandrestore.save")) {
				sender.sendMessage(prefix + ChatColor.RED + " You do not have permission to use this command.");
				return true;
			}
			getConfig().set("enableAutoBackup", false);
			saveConfig();
			automate = getConfig().getBoolean("enableAutoBackup");
			sender.sendMessage(prefix + " Disabled auto backup.");
		}
		if (args[0].equalsIgnoreCase("enableAutoBackup")) {
			if (!sender.hasPermission("tedsbackupandrestore.save")) {
				sender.sendMessage(prefix + ChatColor.RED + " You do not have permission to use this command.");
				return true;
			}
			getConfig().set("enableAutoBackup", true);
			saveConfig();
			automate = getConfig().getBoolean("enableAutoBackup");
			sender.sendMessage(prefix + " Enabled auto Backup");
		}
		return true;
	}

	public void backup(CommandSender sender) {
		currentlySaving = true;
		sender.sendMessage(prefix + " Starting to save directory. Please wait.");
		List<World> autosave = new ArrayList<>();
		for (World loaded : Bukkit.getWorlds()) {
			try {
				loaded.save();
				if (loaded.isAutoSave()) {
					autosave.add(loaded);
					loaded.setAutoSave(false);
				}

			} catch (Exception e) {
			}
		}
		new BukkitRunnable() {
			@Override
			public void run() {
				try {
					try {
						if(backups.listFiles().length > maxSaveFiles){
							for(int i  = 0; i < backups.listFiles().length-maxSaveFiles; i++){
								File oldestBack = firstFileModified(backups);
								sender.sendMessage(prefix + ChatColor.RED + oldestBack.getName()
										+ ": File goes over max amount of files that can be saved.");
								oldestBack.delete();
							}
						}
						for (int j = 0; j < Math.min(maxSaveFiles, backups.listFiles().length - 1); j++) {
							if (folderSize(backups) >= maxSaveSize) {
								File oldestBack = firstFileModified(backups);
								sender.sendMessage(prefix + ChatColor.RED + oldestBack.getName()
										+ ": The current save goes over the max savesize, and so the oldest file has been deleted. If you wish to save older backups, copy them to another location.");
								oldestBack.delete();
							} else {
								break;
							}
						}
					} catch (Error | Exception e) {
					}
					final long time = lastSave = System.currentTimeMillis();
					Date d = new Date(lastSave);
					File zipFile = new File(getBackupFolder(),
							naming_format.replaceAll("%date%", dateformat.format(d)) + ".zip");
					if (!zipFile.exists()) {
						zipFile.getParentFile().mkdirs();
						zipFile = new File(getBackupFolder(),
								naming_format.replaceAll("%date%", dateformat.format(d)) + ".zip");
						zipFile.createNewFile();
					}
					zipFolder(getMasterFolder().getPath(), zipFile.getPath());

					long timeDif = (System.currentTimeMillis() - time) / 1000;
					String timeDifS = (((int) (timeDif / 60)) + "M, " + (timeDif % 60) + "S");

					if(!currentlySaving){
						for (World world : autosave)
							world.setAutoSave(true);
						sender.sendMessage(prefix + " Backup canceled.");
						cancel();
						return;
					}

					sender.sendMessage(prefix + " Done! Backup took:" + timeDifS);
					File tempBackupCheck = new File(getMasterFolder(), "backups");
					sender.sendMessage(prefix + " Compressed server with size of "
							+ (humanReadableByteCount(folderSize(getMasterFolder())
							- (tempBackupCheck.exists() ? folderSize(tempBackupCheck) : 0), false))
							+ " to " + humanReadableByteCount(zipFile.length(), false));
					currentlySaving = false;
					for (World world : autosave)
						world.setAutoSave(true);
					if (useSFTP) {
						try {
							sender.sendMessage(prefix + " Starting SFTP Transfer");
							JSch jsch = new JSch();
							Session session = jsch.getSession(userFTP, serverFTP, portFTP);
							session.setConfig("PreferredAuthentications", "password");
							session.setPassword(passwordFTP);
							session.connect(1000 * 20);
							Channel channel = session.openChannel("sftp");
							ChannelSftp sftp = (ChannelSftp) channel;
							sftp.connect(1000 * 20);
						} catch (Exception | Error e) {
							sender.sendMessage(
									prefix + " FAILED TO SFTP TRANSFER FILE: " + zipFile.getName() + ". ERROR IN CONSOLE.");
							if (deleteZipOnFail)
								zipFile.delete();
							e.printStackTrace();
						}
					} else if (useFTPS) {
						sender.sendMessage(prefix + " Starting FTPS Transfer");
						FileInputStream zipFileStream = new FileInputStream(zipFile);
						FTPSClient ftpClient = new FTPSClient();
						try {
							if (ftpClient.isConnected()) {
								sender.sendMessage(prefix + "FTPSClient was already connected. Disconnecting");
								ftpClient.logout();
								ftpClient.disconnect();
								ftpClient = new FTPSClient();
							}
							sendFTP(sender, zipFile, ftpClient, zipFileStream, removeFilePath);
							if (deleteZipOnFTP)
								zipFile.delete();
						} catch (Exception | Error e) {
							sender.sendMessage(
									prefix + " FAILED TO FTPS TRANSFER FILE: " + zipFile.getName() + ". ERROR IN CONSOLE.");
							if (deleteZipOnFail)
								zipFile.delete();
							e.printStackTrace();
						} finally {
							try {
								if (ftpClient.isConnected()) {
									sender.sendMessage(prefix + "Disconnecting");
									ftpClient.logout();
									ftpClient.disconnect();
								}
							} catch (IOException ex) {
								ex.printStackTrace();
							}
						}
					} else if (useFTP) {
						sender.sendMessage(prefix + " Starting FTP Transfer");
						FileInputStream zipFileStream = new FileInputStream(zipFile);
						FTPClient ftpClient = new FTPClient();
						try {
							if (ftpClient.isConnected()) {
								sender.sendMessage(prefix + "FTPClient was already connected. Disconnecting");
								ftpClient.logout();
								ftpClient.disconnect();
								ftpClient = new FTPClient();
							}
							sendFTP(sender, zipFile, ftpClient, zipFileStream, removeFilePath);
							if (deleteZipOnFTP)
								zipFile.delete();
						} catch (Exception | Error e) {
							sender.sendMessage(
									prefix + " FAILED TO FTP TRANSFER FILE: " + zipFile.getName() + ". ERROR IN CONSOLE.");
							if (deleteZipOnFail)
								zipFile.delete();
							e.printStackTrace();
						} finally {
							try {
								if (ftpClient.isConnected()) {
									sender.sendMessage(prefix + "Disconnecting");
									ftpClient.logout();
									ftpClient.disconnect();
								}
							} catch (IOException ex) {
								ex.printStackTrace();
							}
						}
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}.runTaskAsynchronously(this);
	}

	public void sendFTP(CommandSender sender, File zipFile, FTPClient ftpClient, FileInputStream zipFileStream, String path)
			throws SocketException, IOException {
		ftpClient.connect(serverFTP, portFTP);
		ftpClient.login(userFTP, passwordFTP);
		ftpClient.enterLocalPassiveMode();

		ftpClient.setFileType(FTP.BINARY_FILE_TYPE);

		boolean done = ftpClient.storeFile(path + zipFile.getName(), zipFileStream);
		zipFileStream.close();
		if (done) {
			sender.sendMessage(prefix + " Transfered backup using FTP!");
		} else {
			sender.sendMessage(prefix + " Something failed (maybe)! Status=" + ftpClient.getStatus());
		}

	}

	public long toTime(String time) {
		long militime = 0;
		for(String split : time.split(",")) {
			split = split.trim();
			long k = 1;
			if (split.toUpperCase().endsWith("M")) {
				k *= 60;
			} else if (split.toUpperCase().endsWith("H")) {
				k *= 60 * 60;
			} else if (split.toUpperCase().endsWith("D")) {
				k *= 60 * 60 * 24;
			} else {
				k *= 60 * 60 * 24;
			}
			double j = Double.parseDouble(split.substring(0, split.length() - 1));
			militime += (j*k);
		}
		militime *= 1000;
		return militime;
	}

	public void restore(File backup) {

		//Kick all players
		for (Player player : Bukkit.getOnlinePlayers())
			player.kickPlayer(kickmessage);

		//Disable all plugins safely.
		for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
			if (p != this) {
				try {
					Bukkit.getPluginManager().disablePlugin(p);
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}
		//Unload all worlds.
		List<String> names = new ArrayList<>();
		for (World w : Bukkit.getWorlds()) {
			for (Chunk c : w.getLoadedChunks()) {
				c.unload(false);
			}
			names.add(w.getName());
			Bukkit.unloadWorld(w, true);
		}
		for(String worldnames : names){
			File worldFile = new File(getMasterFolder(),worldnames);
			if(worldFile.exists())
				worldFile.delete();
		}

		//Start overriding files.
		File parentTo = getMasterFolder().getParentFile();
		try {
			byte[] buffer = new byte[1024];
			ZipInputStream zis = new ZipInputStream(new FileInputStream(backup));
			ZipEntry zipEntry = zis.getNextEntry();
			while (zipEntry != null) {
				try {
					File newFile = newFile(parentTo, zipEntry);
					FileOutputStream fos = new FileOutputStream(newFile);
					int len;
					while ((len = zis.read(buffer)) > 0) {
						fos.write(buffer, 0, len);
					}
					fos.close();
					zipEntry = zis.getNextEntry();
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
			zis.closeEntry();
			zis.close();
		} catch (Exception e4) {
			e4.printStackTrace();
		}
		//Bukkit.shutdown();
		Bukkit.spigot().restart();
	}

	public void zipFolder(String srcFolder, String destZipFile) throws Exception {
		ZipOutputStream zip = null;
		FileOutputStream fileWriter = null;

		fileWriter = new FileOutputStream(destZipFile);
		zip = new ZipOutputStream(fileWriter);


		zip.setLevel(compression);

		addFolderToZip("", srcFolder, zip);
		zip.flush();
		zip.close();
	}

	private void addFileToZip(String path, String srcFile, ZipOutputStream zip) {
		try {
			File folder = new File(srcFile);
			if (!isExempt(srcFile)) {

				if(!currentlySaving)
					return;
				// this.savedBytes += folder.length();
				if ((!srcFile.contains(w_world)) && (!srcFile.contains(w_nether)) && (!srcFile.contains(w_end))) {
					//Bukkit.getConsoleSender().sendMessage(folder.toString());
					return;
				}
				if (folder.isDirectory()) {
					addFolderToZip(path, srcFile, zip);
				} else {
					byte[] buf = new byte['?'];

					FileInputStream in = new FileInputStream(srcFile);
					zip.putNextEntry(new ZipEntry(path + separator + folder.getName()));
					int len;
					while ((len = in.read(buf)) > 0) {
						zip.write(buf, 0, len);
					}
					in.close();
				}
			}
		}catch (FileNotFoundException e4){
			Bukkit.getConsoleSender().sendMessage(prefix + " FAILED TO ZIP FILE: " + srcFile+" Reason: "+e4.getClass().getName());
			e4.printStackTrace();
		}catch (IOException e5){
			if(!srcFile.endsWith(".db")) {
				Bukkit.getConsoleSender().sendMessage(prefix + " FAILED TO ZIP FILE: " + srcFile + " Reason: " + e5.getClass().getName());
				e5.printStackTrace();
			}else{
				Bukkit.getConsoleSender().sendMessage(prefix + " Skipping file " + srcFile +" due to another process that has locked a portion of the file");
			}

		}
	}

	private void addFolderToZip(String path, String srcFolder, ZipOutputStream zip) {
		if ((!path.toLowerCase().contains("backups")) && (!isExempt(path))) {
			try {
				File folder = new File(srcFolder);
				String[] arrayOfString;
				int j = (arrayOfString = folder.list()).length;
				for (int i = 0; i < j; i++) {
					if(!currentlySaving)
						break;
					String fileName = arrayOfString[i];
					if (path.equals("")) {
						addFileToZip(folder.getName(), srcFolder + separator + fileName, zip);
					} else {
						addFileToZip(path + separator + folder.getName(), srcFolder +  separator + fileName, zip);
					}
				}
			} catch (Exception e) {
			}
		}
	}

	private long toByteSize(String s) {
		long k = Long.parseLong(s.substring(0, s.length() - 1));
		if (s.toUpperCase().endsWith("G")) {
			k *= 1000 * 1000 * 1000;
		} else if (s.toUpperCase().endsWith("M")) {
			k *= 1000 * 1000;
		} else if (s.toUpperCase().endsWith("K")) {
			k *= 1000;
		} else {
			k *= 10;
		}
		return k;
	}
	private String getDayName(final int dayNumber) {
		if (dayNumber == 1) {
			return "SUNDAY";
		}
		if (dayNumber == 2) {
			return "MONDAY";
		}
		if (dayNumber == 3) {
			return "TUESDAY";
		}
		if (dayNumber == 4) {
			return "WEDNESDAY";
		}
		if (dayNumber == 5) {
			return "THURSDAY";
		}
		if (dayNumber == 6) {
			return "FRIDAY";
		}
		if (dayNumber == 7) {
			return "SATURDAY";
		}
		Bukkit.getConsoleSender().sendMessage("Error while converting number in day.");
		return null;
	}
	public World loadWorld(String worldName) {
		WorldCreator worldCreator = new WorldCreator(worldName);
		Bukkit.getServer().createWorld(worldCreator);
		return Bukkit.getWorld(worldName);
	}
}

