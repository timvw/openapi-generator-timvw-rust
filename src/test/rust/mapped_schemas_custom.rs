//! The crate's own types that `--schema-mappings Filter=crate::custom::Filter,Circle=crate::custom::Circle,Token=crate::custom::Token`
//! puts in place of the schemas Filter, Circle and Token of src/test/resources/mapped_schemas.yaml. `MappedSchemasTest` copies this
//! file to src/custom.rs of the generated crate and declares it in src/lib.rs. A mapped type needs what the generated
//! types around it derive: `Clone`, `Debug`, `PartialEq`, `Serialize` and `Deserialize`; one in place of a schema
//! that is not an object, as a path, query or header parameter, `Display` too.

use serde::{Deserialize, Serialize};

/// Filter, written by hand: a mapped type can use the generated models.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Filter {
    pub name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub mode: Option<crate::models::Mode>,
}

impl Filter {
    /// Something the generated Filter would not have.
    pub fn exact(name: &str) -> Self {
        Filter {
            name: name.to_string(),
            mode: Some(crate::models::Mode::Exact),
        }
    }
}

/// Circle, written by hand: a variant of the discriminated union Shape.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Circle {
    pub kind: String,
    pub radius: f64,
}

/// Token, written by hand in place of a string schema: its JSON is the string, and as a parameter it is sent with
/// `Display`.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(transparent)]
pub struct Token(pub String);

impl std::fmt::Display for Token {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}
