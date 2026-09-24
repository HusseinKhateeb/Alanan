# Phone acceptance check

Use a dedicated test account and test records. Keep the laptop awake, Docker and Alanan running, and Tailscale connected on both devices. This is a manual acceptance check; passing frontend unit tests or desktop emulation does not replace it.

1. Open the private HTTPS address in the phone browser and install the app from the browser menu. Open the installed app and sign in. Close and reopen it; the session should recover while the backend is online.
2. In portrait and landscape, check the home page, New sale, sale details, purchases, expenses, waste, payments, reports, users (admin only), and backups (admin only). Check that controls are readable, reachable, and do not cause horizontal page scrolling. Wide report tables may scroll within their own region.
3. Create a test sale using product search, quantity and Pay full total. Check the total before queuing, then confirm the saved invoice number, invoice detail, PDF and dashboard/report figures. Repeat with a named customer and a partial payment.
4. Start another test sale, disconnect the phone from Tailscale and Wi-Fi/mobile data, then create a new customer on New Sale and queue the invoice. Confirm the customer and sale remain on the device as pending. Close and reopen the installed app while disconnected; the sale-only workspace and queued entry should remain. Reconnect and sign in if prompted. Check that the customer is created once and the sale becomes synced once, with exactly one server invoice linked to that customer.
5. Test a reference that changed while offline. A server rejection should show Needs attention and allow correction without silently creating a second invoice.
6. Download a test backup on the phone only if the device has enough storage. Verify that the Backups page can test a staged copy without replacing the live database. For recovery, use the separate database procedure in [backup-recovery.md](backup-recovery.md).

Record the phone model, OS/browser version, app URL, test date, and any failed step. Do not paste credentials, tokens, customer data, or backup contents into an issue report.
