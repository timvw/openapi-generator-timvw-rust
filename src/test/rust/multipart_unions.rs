//! Multipart unions with a file variant at run time, on the crate generated from src/test/resources/multipart_unions.yaml
//! (`httpStack=tower`, `multipartHelper`). `MultipartFileUnionsTest` copies this file into that crate's tests/ and runs
//! cargo. The file variants hold `timvw_rust_runtime::Upload`s: paths, bytes and streams all go out as file parts. A
//! local HTTP server answers each request with `200` and hands back the requests it read.

use std::path::PathBuf;

use multipart_unions::apis::configuration::Configuration;
use multipart_unions::apis::{skills_api, videos_api};
use multipart_unions::models::{Reference, SkillBodyFiles, VideoBodyReference, VideoBodyVideo};
use timvw_rust_runtime::Upload;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;

/// Answers `count` requests with `200`; returns the requests it read.
async fn serve(count: usize) -> (Configuration, tokio::task::JoinHandle<Vec<String>>) {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let handle = tokio::spawn(async move {
        let mut requests = Vec::new();
        for _ in 0..count {
            let (mut socket, _) = listener.accept().await.unwrap();
            let mut request = Vec::new();
            let mut buf = [0u8; 65536];
            loop {
                let n = socket.read(&mut buf).await.unwrap();
                request.extend_from_slice(&buf[..n]);
                let text = String::from_utf8_lossy(&request).to_string();
                if let Some(end) = text.find("\r\n\r\n") {
                    let length = text[..end]
                        .lines()
                        .find_map(|l| {
                            l.to_ascii_lowercase()
                                .strip_prefix("content-length:")
                                .map(|v| v.trim().parse::<usize>().unwrap())
                        })
                        .expect("a Content-Length, not a chunked body");
                    if request.len() >= end + 4 + length {
                        break;
                    }
                }
                if n == 0 {
                    break;
                }
            }
            let response = "HTTP/1.1 200 OK\r\ncontent-length: 0\r\nconnection: close\r\n\r\n";
            socket.write_all(response.as_bytes()).await.unwrap();
            socket.shutdown().await.unwrap();
            requests.push(String::from_utf8_lossy(&request).to_string());
        }
        requests
    });
    let mut configuration = Configuration::new();
    configuration.base_path = format!("http://127.0.0.1:{port}");
    (configuration, handle)
}

fn body(request: &str) -> &str {
    &request[request.find("\r\n\r\n").unwrap() + 4..]
}

fn temp_file(dir: &tempfile::TempDir, name: &str, contents: &str) -> PathBuf {
    let path = dir.path().join(name);
    std::fs::write(&path, contents).unwrap();
    path
}

#[tokio::test]
async fn a_file_variant_takes_bytes_and_paths() {
    let dir = tempfile::tempdir().unwrap();
    let still = temp_file(&dir, "still.png", "PNGDATA");
    let (configuration, server) = serve(1).await;

    videos_api::video_create()
        .configuration(&configuration)
        // An Upload converts into the union, and so does a PathBuf.
        .video(
            Upload::bytes(b"MP4DATA".to_vec())
                .file_name("clip.mp4")
                .mime("video/mp4"),
        )
        .reference(still)
        .prompt("go")
        .call()
        .await
        .unwrap();

    let request = server.await.unwrap().remove(0);
    let body = body(&request);
    assert!(
        body.contains(
            "name=\"video\"; filename=\"clip.mp4\"\r\nContent-Type: video/mp4\r\n\r\nMP4DATA\r\n"
        ),
        "{body}"
    );
    assert!(
        body.contains(
            "name=\"reference\"; filename=\"still.png\"\r\nContent-Type: image/png\r\n\r\nPNGDATA\r\n"
        ),
        "{body}"
    );
}

#[tokio::test]
async fn an_object_variant_is_still_a_json_part() {
    let (configuration, server) = serve(1).await;
    videos_api::video_create()
        .configuration(&configuration)
        .video(VideoBodyVideo::Reference(Reference {
            id: Some("vid_1".into()),
        }))
        .reference(VideoBodyReference::file(Upload::bytes(&b"x"[..])))
        .prompt("go")
        .call()
        .await
        .unwrap();
    let request = server.await.unwrap().remove(0);
    let body = body(&request);
    assert!(
        body.contains("name=\"video\"\r\nContent-Type: application/json\r\n\r\n{\"id\":\"vid_1\"}\r\n"),
        "{body}"
    );
    assert!(body.contains("name=\"reference\"; filename=\"file\"\r\n\r\nx\r\n"), "{body}");
}

#[tokio::test]
async fn an_array_variant_takes_any_uploads() {
    let dir = tempfile::tempdir().unwrap();
    let manifest = temp_file(&dir, "SKILL.md", "# skill");
    let (configuration, server) = serve(1).await;

    skills_api::skill_create()
        .configuration(&configuration)
        .files(SkillBodyFiles::files([
            Upload::from(manifest),
            Upload::bytes(&b"print()"[..]).file_name("main.py"),
        ]))
        .call()
        .await
        .unwrap();

    let request = server.await.unwrap().remove(0);
    let body = body(&request);
    assert!(
        body.contains("name=\"files[]\"; filename=\"SKILL.md\"\r\nContent-Type: text/markdown\r\n\r\n# skill\r\n"),
        "{body}"
    );
    assert!(body.contains("name=\"files[]\"; filename=\"main.py\""), "{body}");
}

#[test]
fn the_unions_compare_clone_and_have_no_json_form_for_files() {
    let file = SkillBodyFiles::file(PathBuf::from("skill.zip"));
    assert_eq!(file, file.clone());
    assert_eq!(file, SkillBodyFiles::from(PathBuf::from("skill.zip")));
    assert_ne!(file, SkillBodyFiles::files(vec![PathBuf::from("skill.zip")]));
    assert_eq!(file.to_string(), "skill.zip");
    // A file has no JSON form; the other variants keep theirs.
    assert!(serde_json::to_string(&file).is_err());
    let reference = VideoBodyVideo::Reference(Reference::new());
    assert_eq!(serde_json::to_string(&reference).unwrap(), "{}");
    assert_eq!(
        serde_json::from_str::<VideoBodyVideo>("{}").unwrap(),
        reference
    );
    assert!(serde_json::from_str::<SkillBodyFiles>("\"skill.zip\"").is_err());
}
