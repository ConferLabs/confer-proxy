package org.moxie.confer.proxy.tools.documents;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import org.hibernate.validator.cdi.ValidationExtension;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
import org.junit.jupiter.api.Test;
import org.moxie.confer.proxy.producers.ObjectMapperProducer;

class DocumentToolCdiTest {

  @Test
  void documentToolsCanBeInjected() {
    try (WeldContainer ignored = new Weld()
        .disableDiscovery()
        .addExtension(new ValidationExtension())
        .addBeanClasses(
            ObjectMapperProducer.class,
            FileOverviewTool.class,
            FileSearchTool.class,
            FileReadTool.class,
            FileViewTool.class,
            DocumentToolConsumer.class)
        .initialize())
    {
      // Container initialization performs the CDI deployment validation.
    }
  }

  @Dependent
  static class DocumentToolConsumer {

    @Inject
    DocumentToolConsumer(FileOverviewTool fileOverviewTool,
                         FileSearchTool fileSearchTool,
                         FileReadTool fileReadTool,
                         FileViewTool fileViewTool)
    {
    }
  }
}
