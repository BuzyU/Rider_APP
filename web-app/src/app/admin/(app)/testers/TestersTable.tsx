"use client";

import { useState } from "react";

export type ApplicationStatus = "PENDING" | "APPROVED" | "REJECTED";

export interface TesterApplicationRow {
  id: string;
  name: string;
  email: string;
  phone: string | null;
  device_model: string | null;
  motorcycle_model: string | null;
  experience_notes: string | null;
  apk_version: string | null;
  status?: ApplicationStatus;
  download_count: number | null;
  created_at: string;
}

interface TestersTableProps {
  initialApplications: TesterApplicationRow[];
}

export default function TestersTable({
  initialApplications,
}: TestersTableProps) {
  const [applications, setApplications] =
    useState<TesterApplicationRow[]>(initialApplications);
  const [error, setError] = useState<string | null>(null);
  const [updatingId, setUpdatingId] = useState<string | null>(null);

  const updateStatus = async (id: string, status: ApplicationStatus) => {
    setError(null);
    setUpdatingId(id);
    const previous = applications;
    setApplications((prev) =>
      prev.map((app) => (app.id === id ? { ...app, status } : app)),
    );

    try {
      const response = await fetch("/api/tester-application", {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ id, status }),
      });

      if (!response.ok) {
        setApplications(previous);
        const payload = await response.json().catch(() => null);
        setError(payload?.error ?? "Failed to update status.");
      }
    } catch {
      setApplications(previous);
      setError("Network error while updating status.");
    } finally {
      setUpdatingId(null);
    }
  };

  if (!applications.length) {
    return (
      <div className="admin-empty">No tester applications submitted yet.</div>
    );
  }

  return (
    <>
      {error && (
        <div className="admin-error" style={{ marginBottom: "16px" }}>
          {error}
        </div>
      )}
      <table className="admin-table">
        <thead>
          <tr>
            <th>Applicant</th>
            <th>Contact</th>
            <th>Motorcycle & Device</th>
            <th>Status</th>
            <th>Notes</th>
            <th>APK Version</th>
            <th>Date</th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody>
          {applications.map((app) => {
            const currentStatus: ApplicationStatus = app.status || "PENDING";
            return (
              <tr key={app.id}>
                <td>
                  <strong>{app.name}</strong>
                </td>
                <td>
                  <div>{app.email}</div>
                  {app.phone && (
                    <div
                      className="admin-muted"
                      style={{ fontSize: "0.85rem" }}
                    >
                      {app.phone}
                    </div>
                  )}
                </td>
                <td>
                  {app.motorcycle_model && <div>{app.motorcycle_model}</div>}
                  {app.device_model && (
                    <div
                      className="admin-muted"
                      style={{ fontSize: "0.85rem" }}
                    >
                      {app.device_model}
                    </div>
                  )}
                  {!app.motorcycle_model && !app.device_model && (
                    <span className="admin-muted">—</span>
                  )}
                </td>
                <td>
                  <span
                    className={`admin-tag ${
                      currentStatus === "APPROVED"
                        ? "admin-tag--success"
                        : currentStatus === "REJECTED"
                          ? "admin-tag--danger"
                          : ""
                    }`}
                  >
                    {currentStatus}
                  </span>
                </td>
                <td style={{ maxWidth: "220px", wordBreak: "break-word" }}>
                  {app.experience_notes || (
                    <span className="admin-muted">—</span>
                  )}
                </td>
                <td>{app.apk_version || "v0.0.3.13"}</td>
                <td>{new Date(app.created_at).toLocaleDateString()}</td>
                <td>
                  <div style={{ display: "flex", gap: "6px" }}>
                    {currentStatus !== "APPROVED" && (
                      <button
                        type="button"
                        disabled={updatingId === app.id}
                        className="admin-button admin-button--primary"
                        style={{ padding: "4px 10px", fontSize: "0.8rem" }}
                        onClick={() => updateStatus(app.id, "APPROVED")}
                      >
                        Approve
                      </button>
                    )}
                    {currentStatus !== "REJECTED" && (
                      <button
                        type="button"
                        disabled={updatingId === app.id}
                        className="admin-button admin-button--ghost"
                        style={{
                          padding: "4px 10px",
                          fontSize: "0.8rem",
                          color: "#dc2626",
                        }}
                        onClick={() => updateStatus(app.id, "REJECTED")}
                      >
                        Reject
                      </button>
                    )}
                  </div>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </>
  );
}
