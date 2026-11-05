"""Exercise the Python policy with passing and failing source-level boundary cases."""

import unittest

from check_python import inspect_shell, inspect_source


def probe_body(decisions):
    """Create a documented function with an exact number of independent if decisions."""
    return ('"""Probe module."""\ndef run(value):\n    """Exercise independent branches."""\n'
            + '    if value:\n        print(value)\n' * decisions)


class PythonPolicyTest(unittest.TestCase):
    """Verify that policy boundaries and nested-scope handling cannot be bypassed."""

    def test_ten_paths_pass_and_eleven_fail(self):
        """Accept nine decisions plus entry and reject one additional decision."""
        self.assertEqual([], inspect_source(probe_body(9), 'probe.py'))
        self.assertTrue(any('complexity 11' in item
                            for item in inspect_source(probe_body(10), 'probe.py')))

    def test_private_constructor_and_nested_docs_are_required(self):
        """Require docs on modules, classes, constructors, private helpers, and nested functions."""
        source = ('class Probe:\n    def __init__(self):\n        pass\n'
                  '    def _hidden(self):\n        def nested():\n            pass\n')
        failures = inspect_source(source, 'probe.py')
        self.assertEqual(5, len(failures))
        self.assertTrue(all('docstring' in item for item in failures))

    def test_lambdas_and_boolean_decisions_are_measured(self):
        """Reject a field lambda whose short-circuit expression alone exceeds the ceiling."""
        source = '"""Probe module."""\ncallback = lambda value: ' + ' and '.join(['value'] * 11)
        self.assertTrue(any('Lambda has cyclomatic complexity 11' in item
                            for item in inspect_source(source, 'probe.py')))

    def test_nested_callables_are_independent(self):
        """Avoid charging a valid nested lambda's branches to its containing function."""
        source = probe_body(9) + '    return lambda value: ' + ' or '.join(['value'] * 10)
        self.assertEqual([], inspect_source(source, 'probe.py'))

    def test_comprehensions_and_exception_paths_count(self):
        """Count loop filters and exception handlers even in otherwise straight-line code."""
        source = (probe_body(7) + '    try:\n        return [x for x in value if x]\n'
                  '    except ValueError:\n        return []\n')
        self.assertTrue(any('complexity 11' in item for item in inspect_source(source, 'probe.py')))

    def test_match_guards_and_default(self):
        """Count a case and its guard while treating an unguarded wildcard as the default path."""
        source = (probe_body(7) + '    match value:\n'
                  '        case 1 if value:\n            return 1\n'
                  '        case _:\n            return 0\n')
        self.assertEqual([], inspect_source(source, 'probe.py'))
        self.assertTrue(any('complexity 11' in item for item in inspect_source(
            source.replace('case _:', 'case _ if value:'), 'probe.py')))

    def test_module_and_class_initializers_count(self):
        """Reject complex executable initialization outside functions as well."""
        branches = 'if True:\n    print(1)\n' * 10
        self.assertTrue(any('complexity 11' in item for item in inspect_source(
            '"""Probe module."""\n' + branches, 'probe.py')))
        class_body = '\n'.join('    ' + line for line in branches.splitlines())
        self.assertTrue(any('complexity 11' in item for item in inspect_source(
            '"""Probe module."""\nclass Probe:\n    """Probe class."""\n' + class_body, 'probe.py')))

    def test_syntax_errors_fail(self):
        """Treat unparseable Python as a policy failure instead of skipping the file."""
        self.assertTrue(any('invalid Python' in item for item in inspect_source('def broken(', 'probe.py')))

    def test_shell_cannot_hide_unchecked_logic(self):
        """Permit documented delegation but reject added shell branches and missing purpose comments."""
        wrapper = '# Regenerate mappings.\nexec python3 .github/scripts/generate-mappings.py "$@"\n'
        self.assertEqual([], inspect_shell(wrapper, 'probe.sh'))
        self.assertTrue(inspect_shell(wrapper + 'if true; then echo bypass; fi\n', 'probe.sh'))
        self.assertTrue(inspect_shell(wrapper.split('\n', 1)[1], 'probe.sh'))


if __name__ == '__main__':
    unittest.main()
