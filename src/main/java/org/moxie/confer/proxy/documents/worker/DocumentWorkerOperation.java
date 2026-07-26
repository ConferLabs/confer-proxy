package org.moxie.confer.proxy.documents.worker;

public enum DocumentWorkerOperation {

  EXTRACT("extract"),
  OPEN("session_open"),
  OPEN_TEXT("session_open_text"),
  OVERVIEW("session_overview"),
  SEARCH("session_search"),
  READ("session_read"),
  RENDER("session_render"),
  RELEASE("session_release"),
  CLOSE("session_close");

  private final String wireName;

  DocumentWorkerOperation(String wireName) {
    this.wireName = wireName;
  }

  public String wireName() {
    return wireName;
  }
}
