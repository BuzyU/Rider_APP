# Network Diagnostics E2E Test Script for PowerShell
param(
    [string]$BaseUrl = "http://localhost:3000",
    [string]$AdminKey = "test_admin_secret_key_12345"
)

$ErrorActionPreference = "Stop"
$ConvoyName = "Alpha-Convoy-Test"
$TokenRider1 = "test_rider_alpha"
$TokenRider2 = "test_rider_bravo"

Write-Host "========================================================" -ForegroundColor Cyan
Write-Host " Starting Network Diagnostics E2E Test" -ForegroundColor Cyan
Write-Host " Target: $BaseUrl" -ForegroundColor Cyan
Write-Host "========================================================" -ForegroundColor Cyan

# Step 1: Ensure room exists
Write-Host "[1/7] Ensuring convoy '$ConvoyName' exists..." -ForegroundColor Yellow
$headers1 = @{
    "Authorization" = "Bearer $TokenRider1"
    "Content-Type" = "application/json"
}
$initBody = @{ roomName = $ConvoyName } | ConvertTo-Json
$initResp = Invoke-RestMethod -Uri "$BaseUrl/api/rooms/room/token" -Method Post -Headers $headers1 -Body $initBody
Write-Host "Room created/verified: $($initResp.roomName)" -ForegroundColor Green

# Authorize Rider 2 as accepted convoy member
node "$PSScriptRoot\seed_rider_membership.js" $ConvoyName $TokenRider1 $TokenRider2

# Step 2: Rider 1 joins
Write-Host "`n[2/7] Rider 1 joining diagnostic run..." -ForegroundColor Yellow
$joinBody1 = @{ convoyId = $ConvoyName } | ConvertTo-Json
$joinResp1 = Invoke-RestMethod -Uri "$BaseUrl/api/diagnostics/join" -Method Post -Headers $headers1 -Body $joinBody1
$runId = $joinResp1.runId
$testNum = $joinResp1.testNumber
Write-Host "Rider 1 joined run: $runId (Test #$testNum)" -ForegroundColor Green

# Step 3: Rider 2 joins
Write-Host "`n[3/7] Rider 2 joining diagnostic run..." -ForegroundColor Yellow
$headers2 = @{
    "Authorization" = "Bearer $TokenRider2"
    "Content-Type" = "application/json"
}
$joinBody2 = @{ convoyId = $ConvoyName } | ConvertTo-Json
$joinResp2 = Invoke-RestMethod -Uri "$BaseUrl/api/diagnostics/join" -Method Post -Headers $headers2 -Body $joinBody2
Write-Host "Rider 2 joined run: $($joinResp2.runId) (Test #$($joinResp2.testNumber))" -ForegroundColor Green

# Step 4: Rider 1 posts batch 1
Write-Host "`n[4/7] Rider 1 posting Batch 1..." -ForegroundColor Yellow
$batch1 = @{
    runId = $runId
    sequenceNumber = 1
    payload = @{
        selfMetrics = @(
            @{
                timestamp = 1728900000000
                rtt_ms = 42.5
                packets_sent = 100
                packets_received = 98
                packets_lost = 2
                jitter_ms = 3.1
                bytes_sent = 16000
                bytes_received = 15600
                network_type = "CELLULAR"
                cell_operator = "Verizon"
                cell_tech = "LTE"
            }
        )
        inboundMetrics = @(
            @{
                timestamp = 1728900000000
                remote_rider = "Rider_test_rider_bravo"
                track_sid = "TR_001"
                packets_received = 98
                packets_lost = 1
                jitter_ms = 2.8
                bytes_received = 15600
                jitter_buffer_delay = 12.0
                concealed_samples = 0
            }
        )
        events = @(
            @{
                timestamp = 1728900000000
                event_type = "HEALTH_CHANGE"
                details = "CELLULAR_NORMAL"
            }
        )
    }
} | ConvertTo-Json -Depth 5

$bResp1 = Invoke-RestMethod -Uri "$BaseUrl/api/diagnostics/batches" -Method Post -Headers $headers1 -Body $batch1
Write-Host "Rider 1 Batch 1 ingested: $($bResp1.status)" -ForegroundColor Green

# Step 4b: Retransmit Batch 1 (Testing Idempotency)
Write-Host "`n[4b/7] Retransmitting Batch 1 (Testing Idempotency)..." -ForegroundColor Yellow
$bResp1Dup = Invoke-RestMethod -Uri "$BaseUrl/api/diagnostics/batches" -Method Post -Headers $headers1 -Body $batch1
Write-Host "Rider 1 Batch 1 duplicate handled idempotently: $($bResp1Dup.status)" -ForegroundColor Green

# Step 5: Rider 2 posts batch 1
Write-Host "`n[5/7] Rider 2 posting Batch 1..." -ForegroundColor Yellow
$batch2 = @{
    runId = $runId
    sequenceNumber = 1
    payload = @{
        selfMetrics = @(
            @{
                timestamp = 1728900002000
                rtt_ms = 55.0
                packets_sent = 95
                packets_received = 90
                packets_lost = 5
                jitter_ms = 4.5
                bytes_sent = 15200
                bytes_received = 14400
                network_type = "WIFI"
            }
        )
        inboundMetrics = @(
            @{
                timestamp = 1728900002000
                remote_rider = "Rider_test_rider_alpha"
                track_sid = "TR_002"
                packets_received = 90
                packets_lost = 3
                jitter_ms = 3.5
                bytes_received = 14400
            }
        )
        events = @()
    }
} | ConvertTo-Json -Depth 5
$bResp2 = Invoke-RestMethod -Uri "$BaseUrl/api/diagnostics/batches" -Method Post -Headers $headers2 -Body $batch2
Write-Host "Rider 2 Batch 1 ingested: $($bResp2.status)" -ForegroundColor Green

# Step 6: Rider 1 stops recording (with lastSeq)
Write-Host "`n[6/7] Rider 1 stopping recording (lastSeq=1)..." -ForegroundColor Yellow
$stopBody1 = @{ runId = $runId; lastSeq = 1 } | ConvertTo-Json
$stopResp1 = Invoke-RestMethod -Uri "$BaseUrl/api/diagnostics/stop" -Method Post -Headers $headers1 -Body $stopBody1
Write-Host "Rider 1 stopped. Run closed: $($stopResp1.runClosed)" -ForegroundColor Green

# Rider 2 stops recording (with lastSeq) -> triggers run close + ZIP export + email
Write-Host "`n[6b/7] Rider 2 stopping recording (lastSeq=1, triggers run closure)..." -ForegroundColor Yellow
$stopBody2 = @{ runId = $runId; lastSeq = 1 } | ConvertTo-Json
$stopResp2 = Invoke-RestMethod -Uri "$BaseUrl/api/diagnostics/stop" -Method Post -Headers $headers2 -Body $stopBody2
Write-Host "Rider 2 stopped. Run closed: $($stopResp2.runClosed)" -ForegroundColor Green

# Step 7: Export ZIP archive via admin key
Write-Host "`n[7/7] Exporting diagnostic ZIP archive (admin key)..." -ForegroundColor Yellow
$zipOut = "$PSScriptRoot\diagnostic_export_$runId.zip"
$adminHeaders = @{ "x-diag-admin-key" = $AdminKey }
Invoke-RestMethod -Uri "$BaseUrl/api/diagnostics/runs/$runId/export" -Method Get -Headers $adminHeaders -OutFile $zipOut
$fileItem = Get-Item $zipOut
Write-Host "SUCCESS! Exported ZIP archive ($($fileItem.Length) bytes) to $zipOut" -ForegroundColor Green

# List ZIP Entries
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($fileItem.FullName)
Write-Host "`n--- Archive Contents ($($zip.Entries.Count) entries) ---" -ForegroundColor Cyan
foreach ($entry in $zip.Entries) {
    Write-Host " - $($entry.FullName) ($($entry.Length) bytes)" -ForegroundColor Gray
}
$zip.Dispose()
Remove-Item $fileItem.FullName -Force

Write-Host "`n========================================================" -ForegroundColor Cyan
Write-Host " Network Diagnostics E2E Test Completed Successfully!" -ForegroundColor Cyan
Write-Host "========================================================" -ForegroundColor Cyan

