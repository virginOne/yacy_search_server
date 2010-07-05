/**
 *  Search
 *  Copyright 2010 by Michael Peter Christen
 *  First released 25.05.2010 at http://yacy.net
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *  
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *  
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program in the file lgpl21.txt
 *  If not, see <http://www.gnu.org/licenses/>.
 */


package net.yacy.cora.services;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import net.yacy.cora.document.MultiProtocolURI;
import net.yacy.cora.document.RSSFeed;
import net.yacy.cora.document.RSSMessage;
import net.yacy.cora.document.RSSReader;
import net.yacy.cora.protocol.HttpConnector;

import org.apache.commons.httpclient.methods.multipart.Part;
import org.apache.commons.httpclient.methods.multipart.StringPart;

public class Search {
    
    public static BlockingQueue<RSSMessage> search(String rssSearchServiceURL, String query, boolean verify, boolean global, long timeout, int maximumRecords) {
        BlockingQueue<RSSMessage> queue = new LinkedBlockingQueue<RSSMessage>();
        searchJob job = new searchJob(rssSearchServiceURL, query, verify, global, timeout, maximumRecords, queue);
        job.start();
        return queue;
    }
    
    private final static int recordsPerSession = 10;
    
    public static class searchJob extends Thread {

        String urlBase, query;
        boolean verify, global;
        long timeout;
        int startRecord,  maximumRecords;
        BlockingQueue<RSSMessage> queue;

        public searchJob(String urlBase, String query, boolean verify, boolean global, long timeout, int maximumRecords, BlockingQueue<RSSMessage> queue) {
            this.urlBase = urlBase;
            this.query = query;
            this.verify = verify;
            this.global = global;
            this.timeout = timeout;
            this.startRecord = 0;
            this.maximumRecords = maximumRecords;
            this.queue = queue;
        }

        public void run() {
            RSSMessage message;
            mainloop: while (timeout > 0 && maximumRecords > 0) {
                long st = System.currentTimeMillis();
                RSSFeed feed;
                try {
                    feed = search(urlBase, query, verify, global, timeout, startRecord, recordsPerSession);
                } catch (IOException e1) {
                    break mainloop;
                }
                if (feed == null || feed.isEmpty()) break mainloop;
                maximumRecords -= feed.size();
                innerloop: while (!feed.isEmpty()) {
                    message = feed.pollMessage();
                    if (message == null) break innerloop;
                    try {
                        queue.put(message);
                    } catch (InterruptedException e) {
                        break innerloop;
                    }
                }
                startRecord += recordsPerSession;
                timeout -= System.currentTimeMillis() - st;
            }
            try { queue.put(RSSMessage.POISON); } catch (InterruptedException e) {}
        }
    }
    
    /**
     * send a query to a yacy public search interface
     * @param rssSearchServiceURL the target url base (everything before the ? that follows the SRU request syntax properties). can null, then the local peer is used
     * @param query the query as string
     * @param startRecord number of first record
     * @param maximumRecords maximum number of records
     * @param verify if true, result entries are verified using the snippet fetch (slow); if false simply the result is returned
     * @param global if true also search results from other peers are included
     * @param timeout milliseconds that are waited at maximum for a search result
     * @return
     */
    public static RSSFeed search(String rssSearchServiceURL, String query, boolean verify, boolean global, long timeout, int startRecord, int maximumRecords) throws IOException {
        MultiProtocolURI uri = null;
        try {
            uri = new MultiProtocolURI(rssSearchServiceURL);
        } catch (MalformedURLException e) {
            throw new IOException("cora.Search failed asking peer '" + rssSearchServiceURL + "': bad url, " + e.getMessage());
        }
        
        // prepare request
        final List<Part> post = new ArrayList<Part>();
        post.add(new StringPart("query", query, Charset.defaultCharset().name()));
        post.add(new StringPart("startRecord", Integer.toString(startRecord), Charset.defaultCharset().name()));
        post.add(new StringPart("maximumRecords", Long.toString(maximumRecords), Charset.defaultCharset().name()));
        post.add(new StringPart("verify", verify ? "true" : "false", Charset.defaultCharset().name()));
        post.add(new StringPart("resource", global ? "global" : "local", Charset.defaultCharset().name()));
        
        // send request
        try {
            final byte[] result = HttpConnector.wput(rssSearchServiceURL, uri.getHost(), post, (int) timeout);
            //String debug = new String(result); System.out.println("*** DEBUG: " + debug);
            final RSSReader reader = RSSReader.parse(result);
            if (reader == null) {
                throw new IOException("cora.Search failed asking peer '" + uri.getHost() + "': probably bad response from remote peer (1), reader == null");
            }
            final RSSFeed feed = reader.getFeed();
            if (feed == null) {
                // case where the rss reader does not understand the content
                throw new IOException("cora.Search failed asking peer '" + uri.getHost() + "': probably bad response from remote peer (2)");
            }
            return feed;
        } catch (final IOException e) {
            throw new IOException("cora.Search error asking peer '" + uri.getHost() + "':" + e.toString());
        }
    }
    
}