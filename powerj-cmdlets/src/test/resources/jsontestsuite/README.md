# JSONTestSuite

The `test_parsing` files of [nst/JSONTestSuite](https://github.com/nst/JSONTestSuite) by Nicolas Seriot (commit
`1ef36fa01286573e846ac449e8683f8833c5b26a`, MIT license, see `LICENSE`), unchanged, used by `JsonTestSuiteTest`.

- `y_*.json`: valid, must be accepted. `n_*.json`: invalid, must be rejected. `i_*.json`: left open by RFC 8259; what
  PowerJ does with each is pinned in the test.
- Not copied: `n_structure_100000_opening_arrays.json` (100 kB) and `n_structure_open_array_object.json` (250 kB), which
  are nothing but brackets: the test builds them.
- The files are bytes, some of them not valid text: `.gitattributes` keeps git from converting their line endings.
