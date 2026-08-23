//! GCS integration tests using the official google-cloud-storage Rust crate.
//!
//! The Rust SDK is the strictest consumer of media download responses. It
//! rejects any read whose response lacks the x-goog-generation header, so
//! these tests guard the system metadata headers end to end.
//!
//! Only the data plane (upload and download) is covered. Bucket and object
//! administration goes through StorageControl, which speaks the gRPC-only
//! google.storage.v2 API that the emulator does not implement. Those
//! operations stay covered by the Go, Python, Node and Java suites.

use google_cloud_auth::credentials::anonymous;
use google_cloud_storage::client::Storage;
use google_cloud_storage::model_ext::ReadRange;

const BUCKET: &str = "rust-gcs-bucket";
const CONTENT: &str = "Hello, GCP Cloud Storage from Rust!";

async fn storage_client(endpoint: &str) -> anyhow::Result<Storage> {
    Ok(Storage::builder()
        .with_endpoint(endpoint)
        .with_credentials(anonymous::Builder::new().build())
        .build()
        .await?)
}

async fn read_all(
    response: &mut google_cloud_storage::read_object::ReadObjectResponse,
) -> anyhow::Result<Vec<u8>> {
    let mut data = Vec::new();
    while let Some(chunk) = response.next().await.transpose()? {
        data.extend_from_slice(&chunk);
    }
    Ok(data)
}

fn endpoint() -> String {
    std::env::var("STORAGE_EMULATOR_HOST")
        .or_else(|_| std::env::var("FLOCI_GCP_ENDPOINT"))
        .unwrap_or_else(|_| "http://localhost:4588".to_string())
}

fn project() -> String {
    std::env::var("FLOCI_GCP_PROJECT").unwrap_or_else(|_| "test-project".to_string())
}

/// Bucket administration is not part of the data plane client, so the bucket
/// is created through the JSON API directly.
async fn ensure_bucket(endpoint: &str) -> anyhow::Result<()> {
    let response = reqwest::Client::new()
        .post(format!("{endpoint}/storage/v1/b?project={}", project()))
        .json(&serde_json::json!({ "name": BUCKET }))
        .send()
        .await?;
    anyhow::ensure!(
        response.status().is_success() || response.status() == reqwest::StatusCode::CONFLICT,
        "createBucket failed: {}",
        response.status()
    );
    Ok(())
}

#[tokio::test]
async fn read_object_returns_bytes_and_system_metadata() -> anyhow::Result<()> {
    let endpoint = endpoint();
    ensure_bucket(&endpoint).await?;

    let client = storage_client(&endpoint).await?;

    let bucket_path = format!("projects/_/buckets/{BUCKET}");
    let uploaded = client
        .write_object(&bucket_path, "hello.txt", CONTENT)
        .send_unbuffered()
        .await?;
    assert!(uploaded.generation > 0);

    let mut response = client.read_object(&bucket_path, "hello.txt").send().await?;

    // These fields come from the x-goog-* headers of the media response.
    // send() itself fails when x-goog-generation is missing.
    let object = response.object();
    assert_eq!(object.generation, uploaded.generation);
    assert_eq!(object.metageneration, uploaded.metageneration);
    assert_eq!(object.size, CONTENT.len() as i64);
    assert_eq!(object.content_encoding, "identity");
    assert_eq!(object.storage_class, "STANDARD");
    let checksums = object.checksums.expect("x-goog-hash should carry checksums");
    assert!(checksums.crc32c.is_some());
    assert!(!checksums.md5_hash.is_empty());

    assert_eq!(read_all(&mut response).await?, CONTENT.as_bytes());
    Ok(())
}

#[tokio::test]
async fn download_verifies_checksums_from_x_goog_hash() -> anyhow::Result<()> {
    let endpoint = endpoint();
    ensure_bucket(&endpoint).await?;
    let client = storage_client(&endpoint).await?;

    let bucket_path = format!("projects/_/buckets/{BUCKET}");
    client
        .write_object(&bucket_path, "checksummed.txt", CONTENT)
        .send_unbuffered()
        .await?;

    // The SDK recomputes both digests over the streamed bytes and fails the
    // read when they disagree with the values announced in x-goog-hash.
    let mut response = client
        .read_object(&bucket_path, "checksummed.txt")
        .compute_crc32c(true)
        .compute_md5()
        .send()
        .await?;
    assert_eq!(read_all(&mut response).await?, CONTENT.as_bytes());
    Ok(())
}

#[tokio::test]
async fn custom_metadata_round_trips_through_media_headers() -> anyhow::Result<()> {
    let endpoint = endpoint();
    ensure_bucket(&endpoint).await?;
    let client = storage_client(&endpoint).await?;

    let bucket_path = format!("projects/_/buckets/{BUCKET}");
    client
        .write_object(&bucket_path, "meta.txt", CONTENT)
        .set_content_type("text/plain")
        .set_metadata([("originalname", "test.txt"), ("reviewer", "jane")])
        .send_unbuffered()
        .await?;

    // Custom metadata surfaces on downloads as x-goog-meta-* headers.
    let mut response = client.read_object(&bucket_path, "meta.txt").send().await?;
    let metadata = response.object().metadata;
    assert_eq!(metadata.get("originalname").map(String::as_str), Some("test.txt"));
    assert_eq!(metadata.get("reviewer").map(String::as_str), Some("jane"));
    assert_eq!(read_all(&mut response).await?, CONTENT.as_bytes());
    Ok(())
}

#[tokio::test]
async fn ranged_read_returns_partial_bytes_with_metadata() -> anyhow::Result<()> {
    let endpoint = endpoint();
    ensure_bucket(&endpoint).await?;
    let client = storage_client(&endpoint).await?;

    let bucket_path = format!("projects/_/buckets/{BUCKET}");
    let uploaded = client
        .write_object(&bucket_path, "ranged.txt", CONTENT)
        .send_unbuffered()
        .await?;

    // Partial content responses must carry the same x-goog-* headers.
    let mut response = client
        .read_object(&bucket_path, "ranged.txt")
        .set_read_range(ReadRange::segment(7, 3))
        .send()
        .await?;
    let object = response.object();
    assert_eq!(object.generation, uploaded.generation);
    assert_eq!(read_all(&mut response).await?, &CONTENT.as_bytes()[7..10]);
    Ok(())
}

#[tokio::test]
async fn read_pinned_to_generation() -> anyhow::Result<()> {
    let endpoint = endpoint();
    ensure_bucket(&endpoint).await?;
    let client = storage_client(&endpoint).await?;

    let bucket_path = format!("projects/_/buckets/{BUCKET}");
    let uploaded = client
        .write_object(&bucket_path, "pinned.txt", CONTENT)
        .send_unbuffered()
        .await?;

    let mut response = client
        .read_object(&bucket_path, "pinned.txt")
        .set_generation(uploaded.generation)
        .send()
        .await?;
    assert_eq!(response.object().generation, uploaded.generation);
    assert_eq!(read_all(&mut response).await?, CONTENT.as_bytes());
    Ok(())
}

#[tokio::test]
async fn resumable_upload_round_trips() -> anyhow::Result<()> {
    let endpoint = endpoint();
    ensure_bucket(&endpoint).await?;
    let client = storage_client(&endpoint).await?;

    let bucket_path = format!("projects/_/buckets/{BUCKET}");
    let payload = bytes::Bytes::from(vec![b'x'; 1024 * 1024]);

    // A zero threshold forces the resumable upload protocol regardless of
    // payload size. The timeout turns an unreachable session URL into a
    // failure instead of a hang, because the SDK retries forever.
    let upload = client
        .write_object(&bucket_path, "resumable.bin", payload.clone())
        .with_resumable_upload_threshold(0_usize)
        .send_buffered();
    let uploaded = tokio::time::timeout(std::time::Duration::from_secs(60), upload).await??;
    assert_eq!(uploaded.size, payload.len() as i64);

    let mut response = client
        .read_object(&bucket_path, "resumable.bin")
        .compute_crc32c(true)
        .send()
        .await?;
    assert_eq!(response.object().size, payload.len() as i64);
    assert_eq!(read_all(&mut response).await?, payload.as_ref());
    Ok(())
}

#[tokio::test]
async fn resumable_upload_sends_multiple_chunks() -> anyhow::Result<()> {
    let endpoint = endpoint();
    ensure_bucket(&endpoint).await?;
    let client = storage_client(&endpoint).await?;

    let bucket_path = format!("projects/_/buckets/{BUCKET}");
    // The SDK flushes its 8 MiB buffer as soon as it fills, so a larger payload
    // is split across several chunks that share one resumable session. Each
    // intermediate chunk declares the total size.
    let payload = bytes::Bytes::from(vec![b'y'; 9 * 1024 * 1024]);

    let upload = client
        .write_object(&bucket_path, "resumable-chunked.bin", payload.clone())
        .with_resumable_upload_threshold(0_usize)
        .send_buffered();
    let uploaded = tokio::time::timeout(std::time::Duration::from_secs(60), upload).await??;
    assert_eq!(uploaded.size, payload.len() as i64);

    let mut response = client
        .read_object(&bucket_path, "resumable-chunked.bin")
        .send()
        .await?;
    assert_eq!(response.object().size, payload.len() as i64);
    let downloaded = read_all(&mut response).await?;
    assert_eq!(downloaded.len(), payload.len());
    assert!(downloaded.iter().all(|byte| *byte == b'y'));
    Ok(())
}
