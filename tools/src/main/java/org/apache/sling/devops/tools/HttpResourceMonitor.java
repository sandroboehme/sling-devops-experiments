package org.apache.sling.devops.tools;

import java.io.IOException;

import org.apache.http.HttpResponse;
import org.apache.http.NoHttpResponseException;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultHttpRequestRetryHandler;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpResourceMonitor {

	private static final Logger logger = LoggerFactory.getLogger(HttpResourceMonitor.class);

	public static void main(String[] args) {
		final String address = args.length < 1 ? "http://localhost/" : args[0];
		final int numThreads = args.length < 2 ? 10 : Integer.parseInt(args[1]);

		final Runnable runnable = new Runnable() {
			@Override
			public void run() {
				try (final CloseableHttpClient httpClient = HttpClients.custom().setRetryHandler(new DefaultHttpRequestRetryHandler(0, false)).build()) {

					// Custom response handler
					ResponseHandler<String> responseHandler = new ResponseHandler<String>() {
						@Override
						public String handleResponse(final HttpResponse response) throws IOException {
							return String.format(
									"%s - %s",
									response.getStatusLine(),
									EntityUtils.toString(response.getEntity()).replaceAll("\\r?\\n", "\\\\n")
									);
						}
					};

					// Send requests
					String prevResponse = null;
					while (true) {
						String curResponse;
						try {
							curResponse = httpClient.execute(
									new HttpGet(address),
									responseHandler
									);
						} catch (NoHttpResponseException e) {
							curResponse = "No response!";
						} catch (IOException e) {
							curResponse = "Connection aborted!";
						}
						if (prevResponse == null || !prevResponse.equals(curResponse)) {
							logger.info(curResponse);
						}
						prevResponse = curResponse;
					}
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		};

		for (int i = 0; i < numThreads; i++) {
			new Thread(runnable).start();
		}
	}
}
