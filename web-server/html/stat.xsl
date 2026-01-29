<?xml version="1.0" encoding="utf-8" ?>
<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">

<xsl:template match="/">
    <html>
        <head>
            <title>RTMP Statistics</title>
            <style>
                body {
                    font-family: Arial, sans-serif;
                    margin: 20px;
                    background-color: #f5f5f5;
                }
                h1 {
                    color: #333;
                }
                table {
                    border-collapse: collapse;
                    width: 100%;
                    margin-bottom: 20px;
                    background-color: white;
                }
                th {
                    background-color: #4CAF50;
                    color: white;
                    padding: 12px;
                    text-align: left;
                }
                td {
                    border: 1px solid #ddd;
                    padding: 8px;
                }
                tr:nth-child(even) {
                    background-color: #f2f2f2;
                }
                .section {
                    margin-bottom: 30px;
                }
                .status {
                    display: inline-block;
                    padding: 5px 10px;
                    border-radius: 3px;
                    font-weight: bold;
                }
                .status.active {
                    background-color: #4CAF50;
                    color: white;
                }
                .status.inactive {
                    background-color: #f44336;
                    color: white;
                }
            </style>
        </head>
        <body>
            <h1>RTMP Statistics</h1>

            <div class="section">
                <h2>Server Info</h2>
                <table>
                    <tr>
                        <th>Property</th>
                        <th>Value</th>
                    </tr>
                    <tr>
                        <td>nginx Version</td>
                        <td><xsl:value-of select="rtmp/nginx_version"/></td>
                    </tr>
                    <tr>
                        <td>nginx RTMP Version</td>
                        <td><xsl:value-of select="rtmp/nginx_rtmp_version"/></td>
                    </tr>
                    <tr>
                        <td>Built</td>
                        <td><xsl:value-of select="rtmp/built"/></td>
                    </tr>
                    <tr>
                        <td>PID</td>
                        <td><xsl:value-of select="rtmp/pid"/></td>
                    </tr>
                    <tr>
                        <td>Uptime</td>
                        <td><xsl:value-of select="rtmp/uptime"/> seconds</td>
                    </tr>
                </table>
            </div>

            <div class="section">
                <h2>Bandwidth Stats</h2>
                <table>
                    <tr>
                        <th>Direction</th>
                        <th>Bytes</th>
                        <th>Bandwidth (bytes/sec)</th>
                    </tr>
                    <tr>
                        <td>Incoming</td>
                        <td><xsl:value-of select="rtmp/bw_in"/></td>
                        <td><xsl:value-of select="rtmp/bytes_in"/></td>
                    </tr>
                    <tr>
                        <td>Outgoing</td>
                        <td><xsl:value-of select="rtmp/bw_out"/></td>
                        <td><xsl:value-of select="rtmp/bytes_out"/></td>
                    </tr>
                </table>
            </div>

            <div class="section">
                <h2>Active Streams</h2>
                <xsl:apply-templates select="rtmp/server/application"/>
            </div>
        </body>
    </html>
</xsl:template>

<xsl:template match="application">
    <h3>Application: <xsl:value-of select="name"/></h3>
    <xsl:choose>
        <xsl:when test="live/stream">
            <table>
                <tr>
                    <th>Stream Name</th>
                    <th>Time Running</th>
                    <th>Bandwidth In</th>
                    <th>Bandwidth Out</th>
                    <th>Clients</th>
                </tr>
                <xsl:apply-templates select="live/stream"/>
            </table>
        </xsl:when>
        <xsl:otherwise>
            <p><span class="status inactive">No Active Streams</span></p>
        </xsl:otherwise>
    </xsl:choose>
</xsl:template>

<xsl:template match="stream">
    <tr>
        <td>
            <span class="status active">LIVE</span>
            <xsl:text> </xsl:text>
            <xsl:value-of select="name"/>
        </td>
        <td><xsl:value-of select="time"/> ms</td>
        <td><xsl:value-of select="bw_in"/> bytes/sec</td>
        <td><xsl:value-of select="bw_out"/> bytes/sec</td>
        <td>
            <xsl:value-of select="nclients"/> clients
            (<xsl:value-of select="meta/video/codec"/> / <xsl:value-of select="meta/audio/codec"/>)
        </td>
    </tr>
</xsl:template>

</xsl:stylesheet>
