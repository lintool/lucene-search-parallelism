/**
  Modify twitter tools code: sort files.
 */

package lucene_parallelism.lucene_parallelism_core.corpus;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.util.Arrays;

import com.google.common.base.Preconditions;

import cc.twittertools.corpus.data.Status;
import cc.twittertools.corpus.data.StatusStream;
import cc.twittertools.corpus.data.JsonStatusBlockReader;

/**
 * Abstraction for a corpus of statuses. A corpus is assumed to consist of a number of blocks, each
 * represented by a gzipped file within a root directory. This object will allow to caller to read
 * through all blocks, in sorted lexicographic order of the files.
 */
public class JsonStatusCorpusReader implements StatusStream {
  private final File[] files;
  private int nextFile = 0;
  private JsonStatusBlockReader currentBlock = null;

  public JsonStatusCorpusReader(File file) throws IOException {
    Preconditions.checkNotNull(file);

    if (!file.isDirectory()) {
      throw new IOException("Expecting " + file + " to be a directory!");
    }

    files = file.listFiles(new FileFilter() {
      public boolean accept(File path) {
        return path.getName().endsWith(".gz") ? true : false;
      }
    });
    Arrays.sort(files);

    if (files.length == 0) {
      throw new IOException(file + " does not contain any .gz files!");
    }
  }

  /**
   * Returns the next status, or <code>null</code> if no more statuses.
   */
  public Status next() throws IOException {
    if (currentBlock == null) {
      currentBlock = new JsonStatusBlockReader(files[nextFile]);
      nextFile++;
    }

    Status status = null;
    while (true) {
      status = currentBlock.next();
      if (status != null) {
        return status;
      }

      if (nextFile >= files.length) {
        // We're out of files to read. Must be the end of the corpus.
        return null;
      }

      currentBlock.close();
      // Move to next file.
      currentBlock = new JsonStatusBlockReader(files[nextFile]);
      nextFile++;
    }
  }

  public void close() throws IOException {
    currentBlock.close();
  }
}
