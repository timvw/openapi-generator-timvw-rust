// Decodes the same payloads with each generated crate; docs/comparison/generate.sh copies this file into
// each crate as examples/decode.rs, replacing MODELS with the crate's model module.
use MODELS as m;

fn show<T: serde::de::DeserializeOwned + serde::Serialize>(case: &str, json: &str) {
    let result = match serde_json::from_str::<T>(json) {
        Ok(v) => match serde_json::to_string(&v) {
            Ok(back) => format!("ok, re-encodes as {back}"),
            Err(e) => format!("ok, but re-encoding fails: {e}"),
        },
        Err(e) => format!("error: {e}"),
    };
    println!("{case}\t{result}");
}

fn main() {
    show::<m::Message>("message: system", r#"{"role":"system","content":"Be brief."}"#);
    show::<m::Message>("message: user with parts", r#"{"role":"user","content":[{"type":"text","text":"hi"}]}"#);
    show::<m::Message>("message: assistant, content null", r#"{"role":"assistant","content":null}"#);
    show::<m::Message>("message: user, content missing", r#"{"role":"user"}"#);
    show::<m::Message>("message: unknown role", r#"{"role":"developer","content":"x"}"#);
    show::<m::ContentPart>("part: image without url", r#"{"type":"image_url","image_url":{}}"#);
    show::<m::ContentPart>("part: unknown type", r#"{"type":"audio","data":"..."}"#);
    show::<m::ChatCompletion>(
        "completion: new finish_reason",
        r#"{"id":"c1","object":"chat.completion","created":1700000000,"model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"x"},"finish_reason":"content_filter"}]}"#,
    );
    show::<m::Usage>("usage: 3e9 tokens", r#"{"prompt_tokens":1,"completion_tokens":1,"total_tokens":3000000000}"#);
    show::<m::ChatCompletionChunk>(
        "chunk: finish_reason null",
        r#"{"id":"c1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"x"},"finish_reason":null}]}"#,
    );
}
