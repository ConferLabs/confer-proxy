import ast
from pathlib import Path


SOURCE_ROOT = Path(__file__).parents[1] / "src" / "confer_document_worker"
ALLOWED_FUNCTION_MODULES = {
  SOURCE_ROOT / "main.py",
}


def test_each_class_lives_in_a_same_named_file() -> None:
  for path in SOURCE_ROOT.rglob("*.py"):
    tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    classes = [node for node in tree.body if isinstance(node, ast.ClassDef)]

    assert len(classes) <= 1, f"{path} contains more than one class"
    if classes:
      assert path.stem == classes[0].name, f"{classes[0].name} must live in {classes[0].name}.py"


def test_classes_are_not_static_method_namespaces() -> None:
  for path in SOURCE_ROOT.rglob("*.py"):
    tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    for node in ast.walk(tree):
      if not isinstance(node, ast.FunctionDef):
        continue
      decorators = {
        decorator.id
        for decorator in node.decorator_list
        if isinstance(decorator, ast.Name)
      }
      assert "staticmethod" not in decorators, f"{path}:{node.lineno} uses a static method"


def test_only_process_boundaries_use_module_functions() -> None:
  for path in SOURCE_ROOT.rglob("*.py"):
    tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    functions = [node for node in tree.body if isinstance(node, ast.FunctionDef)]
    if functions:
      assert path in ALLOWED_FUNCTION_MODULES, f"{path} contains a module function"


def test_domain_does_not_depend_on_outer_packages() -> None:
  domain_root = SOURCE_ROOT / "domain"
  for path in domain_root.glob("*.py"):
    tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    for node in ast.walk(tree):
      if not isinstance(node, ast.ImportFrom) or node.module is None:
        continue
      if node.module.startswith("confer_document_worker"):
        assert node.module.startswith("confer_document_worker.domain"), (
          f"{path} imports outer package {node.module}"
        )


def test_application_does_not_depend_on_protocol() -> None:
  application_root = SOURCE_ROOT / "application"
  for path in application_root.rglob("*.py"):
    tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    for node in ast.walk(tree):
      if isinstance(node, ast.ImportFrom) and node.module is not None:
        assert not node.module.startswith("confer_document_worker.protocol"), (
          f"{path} imports protocol boundary {node.module}"
        )


def test_package_dependencies_are_acyclic() -> None:
  dependencies: dict[str, set[str]] = {}
  for path in SOURCE_ROOT.rglob("*.py"):
    relative = path.relative_to(SOURCE_ROOT)
    if len(relative.parts) < 2:
      continue
    component = relative.parts[0]
    dependencies.setdefault(component, set())
    tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    for node in ast.walk(tree):
      if not isinstance(node, ast.ImportFrom) or node.module is None:
        continue
      parts = node.module.split(".")
      if len(parts) < 2 or parts[0] != "confer_document_worker":
        continue
      imported_component = parts[1]
      if imported_component != component:
        dependencies[component].add(imported_component)

  visiting: set[str] = set()
  visited: set[str] = set()

  def visit(component: str, path: tuple[str, ...]) -> None:
    if component in visiting:
      cycle_start = path.index(component)
      cycle = " -> ".join((*path[cycle_start:], component))
      raise AssertionError(f"package dependency cycle: {cycle}")
    if component in visited:
      return
    visiting.add(component)
    for dependency in dependencies.get(component, set()):
      visit(dependency, (*path, component))
    visiting.remove(component)
    visited.add(component)

  for component in dependencies:
    visit(component, ())
