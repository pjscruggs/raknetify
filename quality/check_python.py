"""Check Python documentation and per-scope complexity without third-party dependencies.

Every module, class, and function requires a nonempty docstring, including private
helpers and nested functions. Complexity includes short-circuit operators, loops,
comprehensions, exception handlers, conditional expressions, and match alternatives.
Nested callables are measured separately instead of being charged to their parent.
"""

import ast
from pathlib import Path
import re
import shlex
import subprocess
import sys


LIMIT = 10
"""Maximum number of independent paths permitted in an executable Python scope."""

SCOPES = (ast.Module, ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef, ast.Lambda)
"""Nodes checked independently, including executable module and class bodies."""

DOCUMENTED = (ast.Module, ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef)
"""Declarations that can carry Python docstrings and must provide one."""

BRANCHES = (ast.If, ast.IfExp, ast.For, ast.AsyncFor, ast.While, ast.ExceptHandler)
"""Control-flow nodes that add one decision before their descendants are counted."""


def decision_weight(node):
    """Return the decisions introduced by one AST node, excluding its descendants."""
    if isinstance(node, ast.BoolOp):
        return len(node.values) - 1
    if isinstance(node, ast.comprehension):
        return 1 + len(node.ifs)
    if isinstance(node, ast.match_case):
        wildcard = isinstance(node.pattern, ast.MatchAs) and node.pattern.pattern is None
        return int(not wildcard) + int(node.guard is not None)
    return int(isinstance(node, BRANCHES))


class DecisionCounter(ast.NodeVisitor):
    """Count paths in one scope, leaving nested scopes to their own checks."""

    def __init__(self):
        """Start with the single path through a body containing no decisions."""
        self.complexity = 1

    def generic_visit(self, node):
        """Add this node's decisions unless it begins a separately measured scope."""
        if isinstance(node, SCOPES):
            return
        self.complexity += decision_weight(node)
        super().generic_visit(node)


def complexity(scope):
    """Measure one module, class body, function, or lambda without nested callables."""
    counter = DecisionCounter()
    for child in ast.iter_child_nodes(scope):
        counter.visit(child)
    return counter.complexity


def inspect_source(source, filename):
    """Return location-bearing documentation and complexity failures for source text.

    Syntax errors are failures rather than silently skipped files. Documentation
    content still needs review for accuracy and usefulness beyond this presence check.
    """
    try:
        tree = ast.parse(source, filename=filename)
    except SyntaxError as error:
        return [f'{filename}:{error.lineno}: invalid Python: {error.msg}']
    failures = []
    for node in ast.walk(tree):
        if isinstance(node, SCOPES):
            failures.extend(inspect_scope(node, filename))
    return failures


def inspect_scope(node, filename):
    """Check one declaration's documentation and its independent path count."""
    name = getattr(node, 'name', type(node).__name__)
    location = f'{filename}:{getattr(node, "lineno", 1)}: {name}'
    failures = []
    if isinstance(node, DOCUMENTED) and not ast.get_docstring(node):
        failures.append(f'{location} requires a descriptive docstring')
    paths = complexity(node)
    if paths > LIMIT:
        failures.append(f'{location} has cyclomatic complexity {paths}, maximum {LIMIT}')
    return failures


def owned_sources(root, suffix):
    """Find tracked and new files with a suffix without entering external Git submodules."""
    result = subprocess.check_output(
        ['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z'], cwd=root)
    return sorted({root / name.decode('utf-8') for name in result.split(b'\0')
                   if name.endswith(suffix.encode('utf-8'))})


def inspect_shell(source, filename):
    """Keep shell entry points as documented Python delegates with no unchecked logic.

    The standard generated Gradle launchers are maintained by Gradle. Project-owned
    shell scripts delegate to Python so their executable logic receives the same
    documentation and complexity checks as the rest of the build tooling.
    """
    documented = any(line.startswith('# ') and line[2:].strip() for line in source.splitlines())
    try:
        words = shlex.split(source, comments=True)
    except ValueError as error:
        return [f'{filename}: invalid shell wrapper: {error}']
    if not documented:
        return [f'{filename}: requires a purpose comment']
    if len(words) != 4 or words[:2] != ['exec', 'python3'] or words[3] != '$@':
        return [f'{filename}: move executable shell logic into a checked Python script']
    if not re.fullmatch(r'\.github/scripts/[a-zA-Z0-9_-]+\.py', words[2]):
        return [f'{filename}: delegate must name a repository Python script']
    return []


def main():
    """Check every owned Python source and return a failing status for any violation."""
    root = Path(__file__).resolve().parent.parent
    files = owned_sources(root, '.py')
    failures = []
    for path in files:
        failures.extend(inspect_source(path.read_text(encoding='utf-8'), str(path.relative_to(root))))
    for path in owned_sources(root, '.sh'):
        failures.extend(inspect_shell(path.read_text(encoding='utf-8'), str(path.relative_to(root))))
    for failure in failures:
        print(failure)
    print(f'Checked {len(files)} Python files: {len(failures)} violations')
    return int(bool(failures))


if __name__ == '__main__':
    sys.exit(main())
