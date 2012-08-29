/*
 *	This file is part of Transdroid <http://www.transdroid.org>
 *	
 *	Transdroid is free software: you can redistribute it and/or modify
 *	it under the terms of the GNU General Public License as published by
 *	the Free Software Foundation, either version 3 of the License, or
 *	(at your option) any later version.
 *	
 *	Transdroid is distributed in the hope that it will be useful,
 *	but WITHOUT ANY WARRANTY; without even the implied warranty of
 *	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *	GNU General Public License for more details.
 *	
 *	You should have received a copy of the GNU General Public License
 *	along with Transdroid.  If not, see <http://www.gnu.org/licenses/>.
 *	
 */
package org.transdroid.daemon.BitComet;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.protocol.HTTP;
import org.transdroid.daemon.Daemon;
import org.transdroid.daemon.DaemonException;
import org.transdroid.daemon.DaemonSettings;
import org.transdroid.daemon.IDaemonAdapter;
import org.transdroid.daemon.Priority;
import org.transdroid.daemon.Torrent;
import org.transdroid.daemon.TorrentFile;
import org.transdroid.daemon.TorrentStatus;
import org.transdroid.daemon.DaemonException.ExceptionType;
import org.transdroid.daemon.task.AddByFileTask;
import org.transdroid.daemon.task.AddByUrlTask;
import org.transdroid.daemon.task.AddByMagnetUrlTask;
import org.transdroid.daemon.task.DaemonTask;
import org.transdroid.daemon.task.DaemonTaskFailureResult;
import org.transdroid.daemon.task.DaemonTaskResult;
import org.transdroid.daemon.task.DaemonTaskSuccessResult;
import org.transdroid.daemon.task.GetFileListTask;
import org.transdroid.daemon.task.GetFileListTaskSuccessResult;
import org.transdroid.daemon.task.RemoveTask;
import org.transdroid.daemon.task.RetrieveTask;
import org.transdroid.daemon.task.RetrieveTaskSuccessResult;
import org.transdroid.daemon.util.DLog;
import org.transdroid.daemon.util.HttpHelper;

import com.android.internalcopy.http.multipart.Part;
import com.android.internalcopy.http.multipart.MultipartEntity;
import com.android.internalcopy.http.multipart.BitCometFilePart;
import com.android.internalcopy.http.multipart.Utf8StringPart;

/**
 * The daemon adapter for the BitComet torrent client.
 * 
 * @author SeNS (sensboston)
 *
 */
public class BitCometAdapter implements IDaemonAdapter {

	private static final String LOG_NAME = "BitComet daemon";

	private DaemonSettings settings;
	private DefaultHttpClient httpclient;
	
	public BitCometAdapter(DaemonSettings settings) {
		this.settings = settings;
	}
	
	@Override
	public DaemonTaskResult executeTask(DaemonTask task) {
		
		try {
			switch (task.getMethod()) {
			case Retrieve:
				
				// Request all torrents from server
				String result = makeRequest("/panel/task_list");
				return new RetrieveTaskSuccessResult((RetrieveTask) task, parseHttpTorrents(result), null);
				
			case GetFileList:
				
				// Request files listing for a specific torrent
				String fhash = ((GetFileListTask)task).getTargetTorrent().getUniqueID();
				result = makeRequest("/panel/task_detail", new BasicNameValuePair("id", fhash), new BasicNameValuePair("show", "files"));
				return new GetFileListTaskSuccessResult((GetFileListTask) task, parseHttpTorrentFiles(result, fhash));
				
			case AddByFile:

				// Upload a local .torrent file
				String ufile = ((AddByFileTask)task).getFile();
				makeFileUploadRequest("/panel/task_add_bt_result", ufile);
				return new DaemonTaskSuccessResult(task);

			case AddByUrl:

				// Request to add a torrent by URL
				String url = ((AddByUrlTask)task).getUrl();
				makeUploadUrlRequest("/panel/task_add_httpftp_result", url);
				return new DaemonTaskSuccessResult(task);
				
			case AddByMagnetUrl:

				// Request to add a torrent by URL
				String magnetUrl = ((AddByMagnetUrlTask)task).getUrl();
				makeUploadUrlRequest("/panel/task_add_httpftp_result", magnetUrl);
				return new DaemonTaskSuccessResult(task);

			case Remove:

				// Remove a torrent
				RemoveTask removeTask = (RemoveTask) task;
				makeRequest("/panel/task_delete", new BasicNameValuePair("id", removeTask.getTargetTorrent().getUniqueID()), 
						new BasicNameValuePair("action", (removeTask.includingData()? "delete_all": "delete_task")));
				return new DaemonTaskSuccessResult(task);
				
			case Pause:

				// Pause a torrent
				makeRequest("/panel/task_action", new BasicNameValuePair("id", task.getTargetTorrent().getUniqueID()), new BasicNameValuePair("action", "stop"));
				return new DaemonTaskSuccessResult(task);
				
			case Resume:

				// Resume a torrent
				makeRequest("/panel/task_action", new BasicNameValuePair("id", task.getTargetTorrent().getUniqueID()), new BasicNameValuePair("action", "start"));
				return new DaemonTaskSuccessResult(task);
				
			default:
				return new DaemonTaskFailureResult(task, new DaemonException(ExceptionType.MethodUnsupported, task.getMethod() + " is not supported by " + getType()));
			}
		}
		catch (DaemonException e) {
			return new DaemonTaskFailureResult(task, new DaemonException(ExceptionType.ParsingFailed, e.toString()));
		}
	}

	/**
	 * Instantiates an HTTP client with proper credentials that can be used for all Buffalo NAS requests.
	 * @param connectionTimeout The connection timeout in milliseconds
	 * @throws DaemonException On conflicting or missing settings
	 */
	private void initialise(int connectionTimeout) throws DaemonException {

		httpclient = HttpHelper.createStandardHttpClient(settings, true);
	}
	
	/**
	 * Build the URL of the http request from the user settings
	 * @return The URL to request
	 */
	private String buildWebUIUrl(String path) {
		return (settings.getSsl() ? "https://" : "http://") + settings.getAddress() + ":" + settings.getPort() + path;
	}
	
	private String makeRequest(String url, NameValuePair... params) throws DaemonException {

		try {
			
			// Initialize the HTTP client
			if (httpclient == null) {
				initialise(HttpHelper.DEFAULT_CONNECTION_TIMEOUT);
			}

			// Add the parameters to the query string
			boolean first = true;
			for (NameValuePair param : params) {
				if (first) {
					url += "?";
					first = false;
				} else {
					url += "&";
				}
				url += param.getName() + "=" + param.getValue();
			}

			// Make the request
			HttpResponse response = httpclient.execute(new HttpGet(buildWebUIUrl(url)));
			HttpEntity entity = response.getEntity();
			if (entity != null) {
				
				// Read JSON response
				java.io.InputStream instream = entity.getContent();
				String result = HttpHelper.ConvertStreamToString(instream);
				instream.close();
				
				// Return raw result
				return result;
			}

			DLog.d(LOG_NAME, "Error: No entity in HTTP response");
			throw new DaemonException(ExceptionType.UnexpectedResponse, "No HTTP entity object in response.");

		} catch (UnsupportedEncodingException e) {
			throw new DaemonException(ExceptionType.ConnectionError, e.toString());
		} catch (Exception e) {
			DLog.d(LOG_NAME, "Error: " + e.toString());
			throw new DaemonException(ExceptionType.ConnectionError, e.toString());
		}
		
	}

	private boolean makeFileUploadRequest(String path, String file) throws DaemonException {

		try {

			// Initialize the HTTP client
			if (httpclient == null) {
				initialise(HttpHelper.DEFAULT_CONNECTION_TIMEOUT);
			}

			// Get default download file location first
			HttpResponse response = httpclient.execute(new HttpGet(buildWebUIUrl("/panel/task_add_bt")));
			HttpEntity entity = response.getEntity();
			if (entity != null) {
				
				// Read BitComet response
				java.io.InputStream instream = entity.getContent();
				String result = HttpHelper.ConvertStreamToString(instream);
				instream.close();
				
				int idx = result.indexOf("save_path' value='")+18;
				String defaultPath = result.substring(idx, result.indexOf("'>", idx));
			
				// Setup request using POST
				HttpPost httppost = new HttpPost(buildWebUIUrl(path));
				File upload = new File(URI.create(file));
				Part[] parts = { new BitCometFilePart("torrent_file", upload), new Utf8StringPart("save_path", defaultPath) };
				httppost.setEntity(new MultipartEntity(parts, httppost.getParams()));
				
				// Make the request
				response = httpclient.execute(httppost);
				
				entity = response.getEntity();
				if (entity != null) {
					// Check BitComet response
					instream = entity.getContent();
					result = HttpHelper.ConvertStreamToString(instream);
					instream.close();
					if (result.indexOf("failed!") > 0) throw new Exception("Adding torrent file failed");
				}
				
				return response.getStatusLine().getStatusCode() == HttpStatus.SC_OK;
			}
			return false;
	
		} catch (FileNotFoundException e) {
			throw new DaemonException(ExceptionType.FileAccessError, e.toString());
		} catch (Exception e) {
			DLog.d(LOG_NAME, "Error: " + e.toString());
			throw new DaemonException(ExceptionType.ConnectionError, e.toString());
		}
	}

	private boolean makeUploadUrlRequest(String path, String url) throws DaemonException {

		try {

			// Initialize the HTTP client
			if (httpclient == null) {
				initialise(HttpHelper.DEFAULT_CONNECTION_TIMEOUT);
			}

			// Get default download file location first
			HttpResponse response = httpclient.execute(new HttpGet(buildWebUIUrl("/panel/task_add_httpftp")));
			HttpEntity entity = response.getEntity();
			if (entity != null) {
				
				// Read BitComet response
				java.io.InputStream instream = entity.getContent();
				String result = HttpHelper.ConvertStreamToString(instream);
				instream.close();
				
				int idx = result.indexOf("save_path' value='")+18;
				String defaultPath = result.substring(idx, result.indexOf("'>", idx));
			
				// Setup form fields and post request
				HttpPost httppost = new HttpPost(buildWebUIUrl(path));
				
				List<NameValuePair> params = new ArrayList<NameValuePair>();
				params.add(new BasicNameValuePair("url", url));
				params.add(new BasicNameValuePair("save_path", defaultPath)); 
				params.add(new BasicNameValuePair("connection", "5"));
				params.add(new BasicNameValuePair("ReferPage", ""));
				params.add(new BasicNameValuePair("textSpeedLimit", "0"));
				httppost.setEntity(new UrlEncodedFormEntity(params, HTTP.UTF_8));
				
				// Make the request
				response = httpclient.execute(httppost);
				
				entity = response.getEntity();
				if (entity != null) {
					// Check BitComet response
					instream = entity.getContent();
					result = HttpHelper.ConvertStreamToString(instream);
					instream.close();
					if (result.indexOf("failed!") > 0) {
						throw new Exception("Adding URL failed");
					}
				}
				
				return response.getStatusLine().getStatusCode() == HttpStatus.SC_OK;
			}
			return false;
		} catch (Exception e) {
			DLog.d(LOG_NAME, "Error: " + e.toString());
			throw new DaemonException(ExceptionType.ConnectionError, e.toString());
		}
	}

	/**
	 * Parse BitComet HTML page (http response) 
	 * @param response
	 * @return
	 * @throws DaemonException
	 */
	private ArrayList<Torrent> parseHttpTorrents(String response) throws DaemonException  {
		
		ArrayList<Torrent> torrents = new ArrayList<Torrent>();
		
		try {
			
			String[] parts = response.substring(response.indexOf("<TABLE"),response.indexOf("</TABLE>")).replaceAll("</td>", "").replaceAll("</tr>", "").replaceAll("\n", "").split("<tr>");
	
			for (int i=2; i<parts.length; i++) {
				
				String[] subParts = parts[i].replaceAll("<td>", "<td").split("<td");
				
				if (subParts.length == 10 && subParts[1].contains("BT") ) {
					
					String name = subParts[2].substring(subParts[2].indexOf("/panel/task_detail")); 
					name = name.substring(name.indexOf(">")+1, name.indexOf("<"));

					TorrentStatus status = parseStatus(subParts[3]);
					String percenDoneStr = subParts[6];
					String downloadRateStr = subParts[7];
					String uploadRateStr = subParts[8];
					
					long size = convertSize(subParts[5]);
					float percentDone = Float.parseFloat(percenDoneStr.substring(0, percenDoneStr.indexOf("%")));
					long sizeDone = (long) (size * percentDone / 100 );
					
					int rateUp = 1000 * Integer.parseInt(uploadRateStr.substring(0, uploadRateStr.indexOf("kB/s"))); 
					int rateDown = 1000 * Integer.parseInt(downloadRateStr.substring(0, downloadRateStr.indexOf("kB/s")));

					// Unfortunately, there is no info for above values providing by BitComet now, 
					// so we may only send additional request for that
					int leechers = 0;
					int seeders = 0;
					int knownLeechers = 0;
					int knownSeeders = 0;
					int distributed_copies = 0;
					long sizeUp = 0;
					String comment = "";
					Date dateAdded = new Date();
					
					// Comment code below to speedup torrent listing 
					// P.S. feature request to extend torrents info is already sent to the BitComet developers
					//*
					try {
						// Lets make summary request and parse details
						String summary = makeRequest("/panel/task_detail", new BasicNameValuePair("id", ""+(i-2)), new BasicNameValuePair("show", "summary"));
						
						String[] sumParts = summary.substring(summary.indexOf("<div align=\"left\">Value</div></th>")).split("<tr><td>");
						comment = sumParts[7].substring(sumParts[7].indexOf("<td>")+4, sumParts[7].indexOf("</td></tr>"));
							
						// Indexes for date and uploaded size
						int idx = 9;
						int sizeIdx = 12;
							
						if (status == TorrentStatus.Downloading) {
							seeders = Integer.parseInt(sumParts[9].substring(sumParts[9].indexOf("Seeds:")+6, sumParts[9].indexOf("(Max possible")));
							leechers = Integer.parseInt(sumParts[9].substring(sumParts[9].indexOf("Peers:")+6, sumParts[9].lastIndexOf("(Max possible")));
							knownSeeders = Integer.parseInt(sumParts[9].substring(sumParts[9].indexOf("(Max possible:")+14, sumParts[9].indexOf(")")));
							knownLeechers = Integer.parseInt(sumParts[9].substring(sumParts[9].lastIndexOf("(Max possible:")+14, sumParts[9].lastIndexOf(")")));
							idx = 13;
							sizeIdx = 16;
						}
							
						DateFormat df = new SimpleDateFormat("yyyy-mm-dd kk:mm:ss");
						dateAdded = df.parse(sumParts[idx].substring(sumParts[idx].indexOf("<td>")+4, sumParts[idx].indexOf("</td></tr>")));
						//sizeDone =  convertSize(sumParts[sizeIdx].substring(sumParts[sizeIdx].indexOf("<td>")+4, sumParts[sizeIdx].indexOf(" (")));
						sizeUp =  convertSize(sumParts[sizeIdx+1].substring(sumParts[sizeIdx+1].indexOf("<td>")+4, sumParts[sizeIdx+1].indexOf(" (")));
					}
					catch (Exception e) {}
					//*
					
					// Add the parsed torrent to the list
					torrents.add(new Torrent(
							(long)i-2,
							null,
							name,
							status,
							null,
							rateDown,
							rateUp,
							leechers,
							seeders,
							knownLeechers,
							knownSeeders,
							(rateDown == 0? -1: (int) ((size - sizeDone) / rateDown)),
							sizeDone,
							sizeUp,
							size,
							percentDone / 100,
							distributed_copies,
							comment,
							dateAdded,
							null));
				}
			}
		}
		catch (Exception e) {
			throw new DaemonException(ExceptionType.UnexpectedResponse, "Invalid BitComet HTTP response.");
		}
		
		return torrents;
	}

	/**
	 * Parse BitComet HTML page (http response) 
	 * @param response
	 * @return
	 * @throws DaemonException
	 */
	private ArrayList<TorrentFile> parseHttpTorrentFiles(String response, String hash) throws DaemonException {
		
		// Parse response
		ArrayList<TorrentFile> torrentfiles = new ArrayList<TorrentFile>();
		
		try {		
		
			String[] files = response.substring(response.indexOf("Operation Method</div></th>")+27, response.lastIndexOf("</TABLE>")).replaceAll("</td>", "").replaceAll("</tr>", "").split("<tr>");
	
			for (int i = 1; i < files.length; i++) {
				
				String[] fileDetails = files[i].replace(">","").split("<td");
				
				long size = convertSize(fileDetails[4].substring(fileDetails[4].indexOf("&nbsp&nbsp ")+11));
				long sizeDone = 0;
				if (!fileDetails[2].contains("--")) {
					double percentDone = Double.parseDouble(fileDetails[2].substring(0, fileDetails[2].indexOf("%")));
					sizeDone = (long) ( size / 100.0 * percentDone); 
				}
				
				torrentfiles.add(new TorrentFile(
						hash,
						fileDetails[3],
						fileDetails[3],
						settings.getDownloadDir() + fileDetails[3],
						size,
						sizeDone,
						parsePriority(fileDetails[1])));
			}
		}
		catch (Exception e) {
			throw new DaemonException(ExceptionType.UnexpectedResponse, "Invalid BitComet HTTP response.");
		}
		
		// Return the list
		return torrentfiles;
	}

	/**
	 * Returns the size of the torrent, as parsed form some string
	 * @param size The size in a string format, i.e. '691 MB'
	 * @return The size in number of kB
	 */
	private static long convertSize(String size) {
		try {
			if (size.endsWith("GB")) {
				return (long)(Float.parseFloat(size.substring(0, size.indexOf("GB"))) * 1024 * 1024 * 1024);
			} else if (size.endsWith("MB")) {
				return (long)(Float.parseFloat(size.substring(0, size.indexOf("MB"))) * 1024 * 1024);
			} else if (size.endsWith("kB")) {
				return (long)(Float.parseFloat(size.substring(0, size.indexOf("kB"))) * 1024);
			} else if (size.endsWith("B")) {
				return (long)(Float.parseFloat(size.substring(0, size.indexOf("B"))));
			}
		}
		catch (Exception e) { }
		return 0;
	}

	/**
	 * Parse BitComet torrent files priority
	 **/
	private Priority parsePriority(String priority) {
		if (priority.equals("Very High") || priority.equals("High")) {
			return Priority.High;
		} else if (priority.equals("Normal")) {
			return Priority.Normal;
		}
		return Priority.Off;
	}
	
	/**
	 * Parse BitComet torrent status 
	 **/
	private TorrentStatus parseStatus(String state) {
		// Status is given as a descriptive string and an indication if the torrent was stopped/paused
		if (state.equals("stopped")) {
			return TorrentStatus.Paused;
		} else if (state.equals("running")) {
			return TorrentStatus.Downloading;
		}
		return TorrentStatus.Unknown;
	}

	@Override
	public Daemon getType() {
		return settings.getType();
	}

	@Override
	public DaemonSettings getSettings() {
		return this.settings;
	}
	
}