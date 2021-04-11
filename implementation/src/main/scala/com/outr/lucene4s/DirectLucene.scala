package com.outr.lucene4s

import java.nio.file.{Files, Path}

import com.outr.lucene4s.document.DocumentBuilder
import com.outr.lucene4s.field.Field
import com.outr.lucene4s.keyword.KeywordIndexing
import com.outr.lucene4s.query.SearchTerm
import org.apache.lucene.analysis.CharArraySet
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.facet.FacetsConfig
import org.apache.lucene.facet.taxonomy.SearcherTaxonomyManager
import org.apache.lucene.facet.taxonomy.SearcherTaxonomyManager.SearcherAndTaxonomy
import org.apache.lucene.facet.taxonomy.directory.DirectoryTaxonomyWriter
import org.apache.lucene.facet.taxonomy.writercache.TaxonomyWriterCache
import org.apache.lucene.index.IndexWriterConfig.OpenMode
import org.apache.lucene.index.{DirectoryReader, IndexWriter, IndexWriterConfig}
import org.apache.lucene.search.SearcherFactory
import org.apache.lucene.store.{MMapDirectory, NIOFSDirectory}

import scala.collection.JavaConverters._

class DirectLucene(override val uniqueFields: List[String],
                   override val directory: Option[Path] = None,
                   val appendIfExists: Boolean = true,
                   override val defaultFullTextSearchable: Boolean = false,
                   val autoCommit: Boolean = false,
                   override val stopWords: Set[String] = KeywordIndexing.DefaultStopWords,
                   stopWordsIgnoreCase: Boolean = true) extends Lucene {
  override protected[lucene4s] lazy val analyzer = new StandardAnalyzer(new CharArraySet(stopWords.asJava, stopWordsIgnoreCase))

  private lazy val indexPath = directory.map(_.resolve("index"))
  private lazy val taxonomyPath = directory.map(_.resolve("taxonomy"))

  private lazy val indexDirectory = indexPath.map(new NIOFSDirectory(_)).getOrElse(new MMapDirectory(Files.createTempDirectory("lucene-index-")))
  private lazy val taxonomyDirectory = taxonomyPath.map(new NIOFSDirectory(_)).getOrElse(new MMapDirectory(Files.createTempDirectory("lucene-taxonomy-")))

  private lazy val indexWriterConfig = new IndexWriterConfig(analyzer)
    .setOpenMode(if (appendIfExists) OpenMode.CREATE_OR_APPEND else OpenMode.CREATE)
  override protected[lucene4s] lazy val facetsConfig = new FacetsConfig

  override protected[lucene4s] lazy val indexWriter: IndexWriter = new IndexWriter(indexDirectory, indexWriterConfig)
  override protected[lucene4s] lazy val indexReader: DirectoryReader = DirectoryReader.open(indexWriter)
  private[lucene4s] lazy val taxonomyWriterCache: TaxonomyWriterCache = createTaxonomyWriterCache()
  override protected[lucene4s] lazy val taxonomyWriter = new DirectoryTaxonomyWriter(taxonomyDirectory, IndexWriterConfig.OpenMode.CREATE_OR_APPEND, taxonomyWriterCache)
  private[lucene4s] lazy val searcherTaxonomyManager = new SearcherTaxonomyManager(
    indexWriter,
    new SearcherFactory,
    taxonomyWriter
  )

  protected def createTaxonomyWriterCache(): TaxonomyWriterCache = DirectoryTaxonomyWriter.defaultTaxonomyWriterCache()

  override protected[lucene4s] def withSearcherAndTaxonomy[R](f: SearcherAndTaxonomy => R): R = {
    searcherTaxonomyManager.maybeRefreshBlocking()
    val instance = searcherTaxonomyManager.acquire()
    try {
      f(instance)
    } finally {
      searcherTaxonomyManager.release(instance)
    }
  }

  private var listeners: List[LuceneListener] = Nil

  override lazy val fullText: Field[String] = create.field[String]("fullText", sortable = false)

  override def delete(term: SearchTerm): Unit = {
    indexWriter.deleteDocuments(SearchTerm.toLucene(term, this))
    if (autoCommit) {
      commit()
    }
  }

  override def listen(listener: LuceneListener): Unit = synchronized {
    listeners = listeners ::: List(listener)
  }

  override def commit(): Unit = {
    indexWriter.commit()
    taxonomyWriter.commit()
    searcherTaxonomyManager.maybeRefresh()
    listeners.foreach(_.commit())
  }

  /**
    * Deletes all documents in the index.
    */
  override def deleteAll(commit: Boolean = true): Unit = {
    indexWriter.deleteAll()
    listeners.foreach(_.delete())
    if (commit) this.commit()
  }

  override def dispose(): Unit = {
    withSearcherAndTaxonomy { instance =>
      instance.searcher.getIndexReader.close()
    }
    indexWriter.close()
    taxonomyWriter.close()
    indexDirectory.close()
    taxonomyDirectory.close()
  }

  override protected[lucene4s] def indexed(builders: Seq[DocumentBuilder]): Unit = synchronized {
    builders.foreach { builder =>
      listeners.foreach(_.indexed(builder))
    }
    if (autoCommit) {
      commit()
    }
  }
}
