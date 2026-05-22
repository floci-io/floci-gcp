terraform {
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 6.0"
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
# reachable via Terraform custom endpoints — they need REST transcoding.
provider "google" {
  project = var.project
  region  = var.region

  user_project_override = false

  storage_custom_endpoint        = "${var.endpoint}/storage/v1/"
  iam_custom_endpoint            = "${var.endpoint}/"
  secret_manager_custom_endpoint = "${var.endpoint}/"
}
