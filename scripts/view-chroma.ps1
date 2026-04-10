param(
    [string]$Endpoint = "http://localhost:8000",
    [string]$Collection = "nexus_kb_1024",
    [int]$Limit = 10,
    [int]$Offset = 0,
    [switch]$IncludeEmbeddings
)

$ErrorActionPreference = "Stop"

function Normalize-Array {
    param([object]$Value)
    if ($null -eq $Value) { return @() }
    if ($Value -is [System.Array]) { return $Value }
    if ($Value.PSObject -and $Value.PSObject.Properties.Name -contains "value") {
        return $Value.value
    }
    return @($Value)
}

function Trim-EndSlash {
    param([string]$Text)
    if ([string]::IsNullOrWhiteSpace($Text)) { return $Text }
    return $Text.TrimEnd("/")
}

$baseUrl = Trim-EndSlash $Endpoint

Write-Host "Loading Chroma collections..." -ForegroundColor Cyan
$collectionsResponse = Invoke-RestMethod -Uri "$baseUrl/api/v1/collections" -Method Get
$collections = Normalize-Array $collectionsResponse

if ($collections.Count -eq 0) {
    throw "No collections found."
}

$target = $collections | Where-Object { $_.name -eq $Collection } | Select-Object -First 1
if ($null -eq $target) {
    $names = ($collections | ForEach-Object { $_.name }) -join ", "
    throw ("Collection not found: {0}. Available: {1}" -f $Collection, $names)
}

$include = @("documents", "metadatas")
if ($IncludeEmbeddings) {
    $include += "embeddings"
}

$payload = @{
    limit = $Limit
    offset = $Offset
    include = $include
}

Write-Host ("Reading collection: {0} ({1})..." -f $target.name, $target.id) -ForegroundColor Cyan
$result = Invoke-RestMethod `
    -Uri "$baseUrl/api/v1/collections/$($target.id)/get" `
    -Method Post `
    -ContentType "application/json" `
    -Body ($payload | ConvertTo-Json -Depth 8)

$ids = Normalize-Array $result.ids
$documents = Normalize-Array $result.documents
$metadatas = Normalize-Array $result.metadatas
$embeddings = Normalize-Array $result.embeddings

$count = $ids.Count
Write-Host ""
Write-Host ("collection={0} totalReturned={1} limit={2} offset={3} include=[{4}]" -f $target.name, $count, $Limit, $Offset, ($include -join ",")) -ForegroundColor Yellow
Write-Host ""

if ($count -eq 0) {
    Write-Host "No records in this page." -ForegroundColor DarkYellow
    exit 0
}

$rows = @()
for ($i = 0; $i -lt $count; $i++) {
    $doc = ""
    if ($i -lt $documents.Count -and $null -ne $documents[$i]) {
        $doc = [string]$documents[$i]
    }
    $preview = $doc
    if ($preview.Length -gt 120) {
        $preview = $preview.Substring(0, 120) + "..."
    }

    $meta = $null
    if ($i -lt $metadatas.Count) {
        $meta = $metadatas[$i]
    }

    $embeddingDim = ""
    if ($IncludeEmbeddings -and $i -lt $embeddings.Count -and $null -ne $embeddings[$i]) {
        $embeddingDim = (Normalize-Array $embeddings[$i]).Count
    }

    $rows += [PSCustomObject]@{
        Index        = $Offset + $i
        Id           = [string]$ids[$i]
        DocumentId   = if ($meta -and $meta.documentId) { [string]$meta.documentId } else { "" }
        SourceUri    = if ($meta -and $meta.sourceUri) { [string]$meta.sourceUri } else { "" }
        ChunkIndex   = if ($meta -and $meta.chunkIndex -ne $null) { [string]$meta.chunkIndex } else { "" }
        EmbeddingDim = $embeddingDim
        Preview      = $preview
    }
}

$rows | Format-Table -AutoSize
