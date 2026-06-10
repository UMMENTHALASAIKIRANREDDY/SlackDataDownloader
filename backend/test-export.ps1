# Test export for channel D01QQ1USUBA (members: U01Q0CXLYMR, U01Q6BUB4NQ)
# Run while Spring Boot is up: mvn spring-boot:run
# Then run: .\test-export.ps1
# Check the terminal where Spring Boot is running for logs:
#   "Both tokens failed for channel ... slackError=... responseBody=..."

$body = @{
    dmEntries = @(
        @{ channelId = "D01QQ1USUBA"; userId = "U01Q0CXLYMR" }
    )
    groupDms = @()
    fromDate = "2025-01-01"
    toDate   = "2026-02-08"
} | ConvertTo-Json

try {
    $response = Invoke-WebRequest -Uri "http://localhost:8081/api/export/slack-dm-mpim" `
        -Method Post `
        -Body $body `
        -ContentType "application/json" `
        -UseBasicParsing `
        -TimeoutSec 120
    $outPath = "dms-export.zip"
    [System.IO.File]::WriteAllBytes($outPath, $response.Content)
    Write-Host "SUCCESS: Saved $outPath ($($response.Content.Length) bytes)"
} catch {
    Write-Host "ERROR: $($_.Exception.Message)"
    if ($_.Exception.Response) {
        $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
        $reader.BaseStream.Position = 0
        Write-Host "Body: $($reader.ReadToEnd())"
    }
}
