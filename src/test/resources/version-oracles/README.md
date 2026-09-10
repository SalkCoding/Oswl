# Concrete version ordering oracles

`pep440.csv` contains 1,444 pairwise comparisons of 38 synthetic version strings.
It was generated on 2026-09-10 with Python 3.14 and pip's vendored `packaging` 26.2:
`(Version(left) > Version(right)) - (Version(left) < Version(right))`.
The ordered set of inputs is the first-occurrence order of the `left` column, so the
cross product and expected results can be regenerated without a random seed.

This covers epoch, zero padding, development/pre/post releases, normalization aliases,
local numeric/text segments and large integers. It is a version-ordering oracle,
not an oracle for dependency specifiers, environment markers or vulnerability facts.

`packaging` is used only as a local verification tool; its code is not copied into
the application or this fixture. Its original LICENSE allows Apache-2.0 or BSD-2-Clause:
https://github.com/pypa/packaging/blob/main/LICENSE
Local LICENSE, LICENSE.APACHE and LICENSE.BSD were inspected. Copyright Donald Stufft
and individual contributors. No Python or packaging runtime dependency is added to OsWL.

The Java comparator implements the version scheme described at:
https://packaging.python.org/en/latest/specifications/version-specifiers/
