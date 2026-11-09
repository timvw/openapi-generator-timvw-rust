//! A discriminator mapping next to the branches' own tag values, on the crate generated from
//! src/test/resources/union_mapping_and_tag_values.yaml. `SmallFixesTest` copies this file into that crate's tests/.

use small_fixes_fixture::models::Pet;

fn pet(kind: &str) -> Pet {
    serde_json::from_value(serde_json::json!({ "kind": kind })).unwrap_or_else(|e| panic!("{kind}: {e}"))
}

/// The mapping's keys and each branch's own tag values all select their branch.
#[test]
fn every_value_selects_its_branch() {
    for kind in ["cat", "kitty", "feline"] {
        assert!(matches!(pet(kind), Pet::Cat(_)), "{kind}: {:?}", pet(kind));
    }
    for kind in ["dog", "puppy"] {
        assert!(matches!(pet(kind), Pet::Dog(_)), "{kind}: {:?}", pet(kind));
    }
}
