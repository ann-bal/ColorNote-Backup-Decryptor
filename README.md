# ColorNote V8 Backup Decryptor

Decrypts the password-protected backup format tested with **ColorNote 4.8.8**
(backup format **version 8**).

This is a small, community-built tool for exporting a compatible ColorNote
backup locally. It runs in Docker, so Java and Python do not need to be
installed on the host computer.

## Prerequisites and limitations

- Currently tested with **password-protected** ColorNote V8 backups from
  ColorNote 4.8.8.
- Password-less V8 backups have not yet been tested.
- Docker is the only host requirement.
- A copy of the backup is recommended. Your notes stay in the folder you mount
  into Docker; as with any personal export, keep the resulting JSON somewhere
  you are comfortable storing it.

## What it implements

The V8 backup has a 28-byte header followed by encrypted data. The established
decryption path is:

- skip the 28-byte header;
- construct a Bouncy Castle `PBEKeySpec` using the password, the fixed salt
  `ColorNote Fixed Salt`, and 20 iterations;
- derive a 128-bit AES key with Bouncy Castle's
  `PBEWITHMD5AND128BITAES-CBC-OPENSSL` scheme;
- decrypt with `AES/CBC/PKCS5Padding` using that key and an explicit all-zero
  16-byte IV; and
- PKCS5/PKCS7 padding removal.

The tool writes active note records to a valid JSON array and leaves out backup
metadata, keeping the export focused on your notes.

## Usage

Build the isolated image once and run it from the directory containing your backup. Replace the placeholder
values with your own filename and password:

```
docker build -t colornote-v8-decryptor .
```
On Bash
```sh
docker run --rm -it -v "$(pwd):/data" colornote-v8-decryptor /data/backup-copy.backup "your-backup-password" /data/decrypted-output.json
```
On Powershell
```powershell
docker run --rm -it -v "${PWD}:/data" colornote-v8-decryptor /data/backup-copy.backup "your-backup-password" /data/decrypted-output.json
```

## Exit codes

`0` means decryption completed. `2` indicates invalid arguments; `3` indicates
an invalid/unsupported backup or an output-file problem; `4` indicates an
incorrect password or corrupted ciphertext.
