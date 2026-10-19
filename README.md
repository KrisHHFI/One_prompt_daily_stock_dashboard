# Stock Dashboard

A minimal Android stock watchlist.

- Add tickers using the box at the top. Your list is saved **only on your phone**.
- Each row shows the current price, plus today's change and % change against the previous close (green for up, red for down).
- Prices refresh automatically every minute while the app is open. Tap **Refresh** to update straight away.
- Hold a row to remove it.

## Tickers

The app uses Yahoo Finance ticker format:

| Market | Example |
|---|---|
| US | `AAPL`, `MSFT` |
| Paris | `MC.PA` |
| Tokyo | `7203.T` |
| London | `VOD.L` |
| Germany | `SAP.DE`, `BMW.DE` |
| Indices | `^GSPC` (S&P 500), `^FTSE` |
| Crypto / FX | `BTC-USD`, `EURUSD=X` |

## Data and privacy

- Prices come from Yahoo Finance's public chart endpoint. No account or API key is needed. The endpoint is free but unofficial, so Yahoo could change it. Quotes may be delayed by up to about 15 minutes, depending on the exchange.
- The only thing the app sends out is the ticker symbol in each price request. There are no ads, no analytics and no third-party libraries.
- Your watchlist is stored in the app's private storage and is not backed up anywhere.

## Get the APK

Every push to this repo builds the app with GitHub Actions and publishes `StockDashboard.apk` under **Releases**. Download it on your phone and tap it to install (allow installs from your browser; if Play Protect warns, choose "Install anyway").
