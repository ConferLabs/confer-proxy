package org.moxie.confer.proxy.documents;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.moxie.confer.proxy.documents.worker.DocumentWorkerScheduler;
import org.moxie.confer.proxy.entities.DocumentReference;
import org.moxie.confer.proxy.images.TemporaryImageStorage;

import java.util.List;

@ApplicationScoped
public class DocumentToolSessionFactory {

  private final DocumentWorkerScheduler workers;
  private final TemporaryImageStorage   images;

  @Inject
  public DocumentToolSessionFactory(DocumentWorkerScheduler workers,
                                    TemporaryImageStorage images)
  {
    this.workers = workers;
    this.images  = images;
  }

  public DocumentToolSession open(List<DocumentReference> references)
    throws InvalidDocumentManifestException
  {
    DocumentManifest manifest = new DocumentManifest(references);
    return new DocumentToolSession(manifest, workers, images);
  }
}
