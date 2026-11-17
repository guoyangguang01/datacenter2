# InfluxDB 2.7.12 runtime

This directory can be shipped inside the server package or installed separately.

Default endpoint:

- URL: `http://127.0.0.1:8087`
- Organization: `sdncustom`
- Bucket: `sdncustom`
- Token: `sdncustom-dev-token`
- Administrator: `admin`
- Password: `sdncustom-admin`

Windows:

```powershell
.\start-influxdb.ps1
.\stop-influxdb.ps1
```

Linux:

```bash
bash ./start-influxdb.sh
bash ./stop-influxdb.sh
```

The first start initializes the organization, bucket, administrator, and token.
Runtime data is stored in `data/` and logs in `logs/`. Keep `data/` when upgrading.

To use an independently installed or remote InfluxDB instead, do not start this
copy and override the server settings with:

```text
SdnCustom__Influx__Url=http://127.0.0.1:8087
SdnCustom__Influx__Token=<token>
SdnCustom__Influx__Org=sdncustom
SdnCustom__Influx__Bucket=sdncustom
```

Replace the development password and token before production use.
