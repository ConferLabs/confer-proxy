from enum import Enum


class DocumentWorkerOperation(Enum):
  EXTRACT = "extract"
  OPEN = "session_open"
  OPEN_TEXT = "session_open_text"
  OVERVIEW = "session_overview"
  SEARCH = "session_search"
  READ = "session_read"
  RENDER = "session_render"
  RELEASE = "session_release"
  CLOSE = "session_close"
