terraform {
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 7.36"
    }
  }
}

variable "endpoint" {
  type    = string
  default = "http://localhost:4588"
}

variable "project" {
  type    = string
  default = "test-project"
}

variable "region" {
  type    = string
  default = "us-central1"
}

# Credentials are provided via GOOGLE_OAUTH_ACCESS_TOKEN env var (fake value —
# floci-gcp ignores auth headers unconditionally).
#
# Custom endpoints redirect each service API to the local emulator.
# Services that only expose gRPC (Pub/Sub, Firestore, Datastore) are not
# reachable via OpenTofu custom endpoints — they need REST transcoding.
provider "google" {
  project = var.project
  region  = var.region

  user_project_override = false

  storage_custom_endpoint        = "${var.endpoint}/storage/v1/"
  iam_custom_endpoint            = "${var.endpoint}/"
  iam_beta_custom_endpoint       = "${var.endpoint}/v1/"
  secret_manager_custom_endpoint = "${var.endpoint}/v1/"
  sql_custom_endpoint            = "${var.endpoint}/sql/v1beta4/"
}
