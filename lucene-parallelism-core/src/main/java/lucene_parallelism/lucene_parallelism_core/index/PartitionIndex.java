/* Divide collection into equal size and build index on each of them
 * Usage: sh target/appassembler/bin/PartitionIndex -collection [] -index [] {-ratio [] -parts []} -optimize
 */

package lucene_parallelism.lucene_parallelism_core.index;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.log4j.Logger;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.IntField;
import org.apache.lucene.document.LongField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.FieldInfo.IndexOptions;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;
import org.apache.tools.bzip2.CBZip2InputStream;

import lucene_parallelism.lucene_parallelism_core.corpus.JsonStatusCorpusReader;
import cc.twittertools.corpus.data.Status;
import cc.twittertools.corpus.data.StatusStream;
import cc.twittertools.index.TweetAnalyzer;

/**
 * Reference implementation for indexing statuses.
 */
public class PartitionIndex {
	private static final Logger LOG = Logger.getLogger(PartitionIndex.class);
	public static final Analyzer ANALYZER = new TweetAnalyzer(Version.LUCENE_43);
	
	public static String corpusFormat = null;
	private static final int NUM_DOCS = 259057030; //16141812;

	private PartitionIndex() {}

	public static enum StatusField {
		ID("id"),
		SCREEN_NAME("screen_name"),
		EPOCH("epoch"),
		TEXT("text"),
		LANG("lang"),
		IN_REPLY_TO_STATUS_ID("in_reply_to_status_id"),
		IN_REPLY_TO_USER_ID("in_reply_to_user_id"),
		FOLLOWERS_COUNT("followers_count"),
		FRIENDS_COUNT("friends_count"),
		STATUSES_COUNT("statuses_count"),
		RETWEETED_STATUS_ID("retweeted_status_id"),
		RETWEETED_USER_ID("retweeted_user_id"),
		RETWEET_COUNT("retweet_count");

		public final String name;

		StatusField(String s) {
			name = s;
		}
	};

	private static final String HELP_OPTION = "h";
	private static final String COLLECTION_OPTION = "collection";
	private static final String INDEX_OPTION = "index";
	private static final String MAX_ID_OPTION = "max_id";
	private static final String DELETES_OPTION = "deletes";
	private static final String OPTIMIZE_OPTION = "optimize";
	private static final String STORE_TERM_VECTORS_OPTION = "store";
	private static final String RATIO_OPTION = "ratio";
	private static final String PARTS_OPTION = "parts";

	@SuppressWarnings("static-access")
	public static void main(String[] args) throws Exception {
		Options options = new Options();

		options.addOption(new Option(HELP_OPTION, "show help"));
		options.addOption(new Option(OPTIMIZE_OPTION, "merge indexes into a single segment"));
		options.addOption(new Option(STORE_TERM_VECTORS_OPTION, "store term vectors"));

		options.addOption(OptionBuilder.withArgName("dir").hasArg()
				.withDescription("source collection directory").create(COLLECTION_OPTION));
		options.addOption(OptionBuilder.withArgName("dir").hasArg()
				.withDescription("index location").create(INDEX_OPTION));
		options.addOption(OptionBuilder.withArgName("file").hasArg()
				.withDescription("file with deleted tweetids").create(DELETES_OPTION));
		options.addOption(OptionBuilder.withArgName("id").hasArg()
				.withDescription("max id").create(MAX_ID_OPTION));
		options.addOption(OptionBuilder.withArgName("arg").hasArg()
				.withDescription("ratio").create(RATIO_OPTION));
		options.addOption(OptionBuilder.withArgName("arg").hasArg()
				.withDescription("partition numbers").create(PARTS_OPTION));

		CommandLine cmdline = null;
		CommandLineParser parser = new GnuParser();
		try {
			cmdline = parser.parse(options, args);
		} catch (ParseException exp) {
			System.err.println("Error parsing command line: " + exp.getMessage());
			System.exit(-1);
		}

		if (cmdline.hasOption(HELP_OPTION) || !cmdline.hasOption(COLLECTION_OPTION)
				|| !cmdline.hasOption(INDEX_OPTION)) {
			HelpFormatter formatter = new HelpFormatter();
			formatter.printHelp(PartitionIndex.class.getName(), options);
			System.exit(-1);
		}

		String collectionPath = cmdline.getOptionValue(COLLECTION_OPTION);
		String indexPath = cmdline.getOptionValue(INDEX_OPTION);

		final FieldType textOptions = new FieldType();
		textOptions.setIndexed(true);
		textOptions.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS);
		textOptions.setStored(true);
		textOptions.setTokenized(true);        
		if (cmdline.hasOption(STORE_TERM_VECTORS_OPTION)) {
			textOptions.setStoreTermVectors(true);
		}

		LOG.info("collection: " + collectionPath);
		LOG.info("index: " + indexPath);

		LongOpenHashSet deletes = null;
		if (cmdline.hasOption(DELETES_OPTION)) {
			deletes = new LongOpenHashSet();
			File deletesFile = new File(cmdline.getOptionValue(DELETES_OPTION));
			if (!deletesFile.exists()) {
				System.err.println("Error: " + deletesFile + " does not exist!");
				System.exit(-1);
			}
			LOG.info("Reading deletes from " + deletesFile);

			FileInputStream fin = new FileInputStream(deletesFile);
			byte[] ignoreBytes = new byte[2];
			fin.read(ignoreBytes); // "B", "Z" bytes from commandline tools
			BufferedReader br = new BufferedReader(new InputStreamReader(new CBZip2InputStream(fin)));

			String s;
			while ((s = br.readLine()) != null) {
				if (s.contains("\t")) {
					deletes.add(Long.parseLong(s.split("\t")[0]));
				} else {
					deletes.add(Long.parseLong(s));
				}
			}
			br.close();
			fin.close();
			LOG.info("Read " + deletes.size() + " tweetids from deletes file.");
		}

		long maxId = Long.MAX_VALUE;
		if (cmdline.hasOption(MAX_ID_OPTION)) {
			maxId = Long.parseLong(cmdline.getOptionValue(MAX_ID_OPTION));
			LOG.info("index: " + maxId);
		}
		
		float ratio = cmdline.hasOption(RATIO_OPTION) ? Float.parseFloat(cmdline.getOptionValue(RATIO_OPTION)) : 1;
		int numDocs = (int)(NUM_DOCS * ratio);
//		int parts = cmdline.hasOption(PARTS_OPTION) ? Integer.parseInt(cmdline.getOptionValue(PARTS_OPTION)) : 1;
//		int size = (int) Math.ceil((float)(NUM_DOCS) / parts);
		
		long startTime = System.currentTimeMillis();
		File file = new File(collectionPath);
		if (!file.exists()) {
			System.err.println("Error: " + file + " does not exist!");
			System.exit(-1);
		}
		
		StatusStream stream = new JsonStatusCorpusReader(file);
		int cnt = 0;
		Status status;
//		try {
//			while ((status = stream.next()) != null) {
//				if (status.getText() == null) {
//					continue;
//				}
//
//				// Skip deletes tweetids.
//				if (deletes != null && deletes.contains(status.getId())) {
//					continue;
//				}
//
//				if (status.getId() > maxId) {
//					continue;
//				}
//				cnt ++;
//				if (cnt % 1000000 == 0) {
//					LOG.info(cnt + " statuses indexed");
//				}
//			}
//			LOG.info(String.format("Total of %s statuses added", cnt));
//		} catch (Exception e) {
//			e.printStackTrace();
//		} finally {
//			stream.close();
//		}
//		int NUM_DOCS = cnt;
//
//		int partCount = 1;
//		Directory dir = FSDirectory.open(new File(indexPath + "/part" + (partCount ++)));
		int[] partsArr = {1, 2, 4, 8, 12, 16, 24, 32, 40, 48, 64, 80, 128};
		int[] size = new int[partsArr.length];
		for (int i = 0; i < partsArr.length; i ++) {
			size[i] = (int)Math.ceil(numDocs * 1.0 / partsArr[i]);
		}
//		int[] size = {129528515, 64764258, 32382129, 21588086, 16191065, 10794043, 8095533, 6476426, 5397022, 4047767, 3238213, 2023884}; //new int[partsArr.length];
		String[] indexPathArr = new String[partsArr.length];
		int[] partCountArr = new int[partsArr.length];
		Directory[] dir = new Directory[partsArr.length];
		IndexWriter[] writer = new IndexWriter[partCountArr.length];
		IndexWriterConfig config = new IndexWriterConfig(Version.LUCENE_43, PartitionIndex.ANALYZER);
		config.setOpenMode(OpenMode.CREATE);
		for (int i = 0; i < partsArr.length; i ++) {
//			size[i] = (int) Math.ceil((double)(NUM_DOCS) / partsArr[i]);
			indexPathArr[i] = indexPath + "-" + partsArr[i] + "parts";
			partCountArr[i] = 1;
			dir[i] = FSDirectory.open(new File(indexPathArr[i] + "/part" + (partCountArr[i] ++)));
			writer[i] = new IndexWriter(dir[i], config);
		}
	
		cnt = 0;
		try {
			while ((status = stream.next()) != null) {
				if (status.getText() == null) {
					continue;
				}

				// Skip deletes tweetids.
				if (deletes != null && deletes.contains(status.getId())) {
					continue;
				}

				if (status.getId() > maxId) {
					continue;
				}

				for (int i = 0; i < partsArr.length; i ++) {
					if (cnt % size[i] == 0 && cnt != 0) {
						writer[i].close();
						dir[i].close();
						dir[i] = FSDirectory.open(new File(indexPathArr[i] + "/part" + (partCountArr[i] ++)));
						writer[i] = new IndexWriter(dir[i], config);
					}
				}
				cnt ++;
				Document doc = new Document();
				doc.add(new LongField(StatusField.ID.name, status.getId(), Field.Store.YES));
				doc.add(new LongField(StatusField.EPOCH.name, status.getEpoch(), Field.Store.YES));
				doc.add(new TextField(StatusField.SCREEN_NAME.name, status.getScreenname(), Store.YES));

				doc.add(new Field(StatusField.TEXT.name, status.getText(), textOptions));

				doc.add(new IntField(StatusField.FRIENDS_COUNT.name, status.getFollowersCount(), Store.YES));
				doc.add(new IntField(StatusField.FOLLOWERS_COUNT.name, status.getFriendsCount(), Store.YES));
				doc.add(new IntField(StatusField.STATUSES_COUNT.name, status.getStatusesCount(), Store.YES));

				long inReplyToStatusId = status.getInReplyToStatusId();
				if (inReplyToStatusId > 0) {
					doc.add(new LongField(StatusField.IN_REPLY_TO_STATUS_ID.name, inReplyToStatusId, Field.Store.YES));
					doc.add(new LongField(StatusField.IN_REPLY_TO_USER_ID.name, status.getInReplyToUserId(), Field.Store.YES));
				}

				String lang = status.getLang();
				if (!lang.equals("unknown")) {
					doc.add(new TextField(StatusField.LANG.name, status.getLang(), Store.YES));
				}

				long retweetStatusId = status.getRetweetedStatusId();
				if (retweetStatusId > 0) {
					doc.add(new LongField(StatusField.RETWEETED_STATUS_ID.name, retweetStatusId, Field.Store.YES));
					doc.add(new LongField(StatusField.RETWEETED_USER_ID.name, status.getRetweetedUserId(), Field.Store.YES));
					doc.add(new IntField(StatusField.RETWEET_COUNT.name, status.getRetweetCount(), Store.YES));
					if ( status.getRetweetCount() < 0 || status.getRetweetedStatusId() < 0) {
						LOG.warn("Error parsing retweet fields of " + status.getId());
					}
				}

				for (int i = 0; i < partCountArr.length; i ++) {
					writer[i].addDocument(doc);
				}
				if (cnt % 100000 == 0) {
					LOG.info(cnt + " statuses indexed");
				}
				if (cnt == numDocs) break;
			}

			LOG.info(String.format("Total of %s statuses added", cnt));

			if (cmdline.hasOption(OPTIMIZE_OPTION)) {
				LOG.info("Merging segments...");
				for (int i = 0; i < partCountArr.length; i ++) {
					writer[i].forceMerge(1);
				}
				LOG.info("Done!");
			}

			LOG.info("Total elapsed time: " + (System.currentTimeMillis() - startTime) + "ms");
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			for (int i = 0; i < partsArr.length; i ++) {
				writer[i].close();
				dir[i].close();
			}
			stream.close();
		}
	}
}