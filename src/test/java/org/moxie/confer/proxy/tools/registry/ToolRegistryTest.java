package org.moxie.confer.proxy.tools.registry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.moxie.confer.proxy.tools.ToolRequirement;
import org.moxie.confer.proxy.tools.documents.FileOverviewTool;
import org.moxie.confer.proxy.tools.documents.FileReadTool;
import org.moxie.confer.proxy.tools.documents.FileSearchTool;
import org.moxie.confer.proxy.tools.documents.FileViewTool;
import org.moxie.confer.proxy.tools.web.PageFetchTool;
import org.moxie.confer.proxy.tools.web.WebSearchTool;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolRegistryTest {

  @Mock
  private WebSearchTool webSearch;

  @Mock
  private PageFetchTool pageFetch;

  @Mock
  private FileOverviewTool fileOverview;

  @Mock
  private FileSearchTool fileSearch;

  @Mock
  private FileReadTool fileRead;

  @Mock
  private FileViewTool fileView;

  private ToolRegistry registry;

  @BeforeEach
  void setUp() {
    when(webSearch.getName()).thenReturn("web_search");
    lenient().when(webSearch.getRequirements()).thenReturn(Set.of(ToolRequirement.WEB_ACCESS));
    when(pageFetch.getName()).thenReturn("page_fetch");
    lenient().when(pageFetch.getRequirements()).thenReturn(Set.of(ToolRequirement.WEB_ACCESS));
    when(fileOverview.getName()).thenReturn("file_overview");
    lenient().when(fileOverview.getRequirements()).thenReturn(Set.of(ToolRequirement.DOCUMENTS));
    when(fileSearch.getName()).thenReturn("file_search");
    lenient().when(fileSearch.getRequirements()).thenReturn(Set.of(ToolRequirement.DOCUMENTS));
    when(fileRead.getName()).thenReturn("file_read");
    lenient().when(fileRead.getRequirements()).thenReturn(Set.of(ToolRequirement.DOCUMENTS));
    when(fileView.getName()).thenReturn("file_view");
    lenient().when(fileView.getRequirements()).thenReturn(Set.of(ToolRequirement.DOCUMENTS));
    registry = new ToolRegistry(
        webSearch,
        pageFetch,
        fileOverview,
        fileSearch,
        fileRead,
        fileView);
  }

  @Test
  void forRequest_excludesToolsWithUnsatisfiedRequirements() {
    RequestToolSet tools = registry.forRequest(new ToolEligibility(Set.of()));

    assertTrue(tools.values().isEmpty());
    assertFalse(tools.contains("web_search"));
    assertFalse(tools.contains("file_search"));
    assertTrue(tools.find("web_search").isEmpty());
  }

  @Test
  void forRequest_includesAllEligibleRegisteredTools() {
    RequestToolSet tools = registry.forRequest(
        new ToolEligibility(Set.of(
            ToolRequirement.WEB_ACCESS,
            ToolRequirement.DOCUMENTS)));

    assertEquals(
        List.of(webSearch, pageFetch, fileOverview, fileSearch, fileRead, fileView),
        tools.values());
    assertSame(fileSearch, tools.find("file_search").orElseThrow());
  }

  @Test
  void constructor_rejectsDuplicateServerToolNames() {
    FileOverviewTool duplicate = mock(FileOverviewTool.class);
    when(duplicate.getName()).thenReturn("web_search");

    IllegalStateException error = assertThrows(
        IllegalStateException.class,
        () -> new ToolRegistry(
            webSearch,
            pageFetch,
            duplicate,
            fileSearch,
            fileRead,
            fileView));

    assertEquals("Duplicate server tool name: web_search", error.getMessage());
  }

  @Test
  void constructor_rejectsBlankServerToolNameAsWiringFailure() {
    when(webSearch.getName()).thenReturn(" ");

    IllegalStateException error = assertThrows(
        IllegalStateException.class,
        () -> new ToolRegistry(
            webSearch,
            pageFetch,
            fileOverview,
            fileSearch,
            fileRead,
            fileView));

    assertEquals("Server tool name must not be blank", error.getMessage());
  }

  @Test
  void toolEligibility_copiesSatisfiedRequirements() {
    Set<ToolRequirement> requirements = EnumSet.of(ToolRequirement.WEB_ACCESS);
    ToolEligibility eligibility = new ToolEligibility(requirements);

    requirements.clear();

    assertTrue(eligibility.satisfies(Set.of(ToolRequirement.WEB_ACCESS)));
  }

  @Test
  void requestToolSet_exposesAnImmutableToolList() {
    RequestToolSet tools = registry.forRequest(
        new ToolEligibility(Set.of(ToolRequirement.WEB_ACCESS)));

    assertThrows(UnsupportedOperationException.class, () -> tools.values().clear());
  }
}
