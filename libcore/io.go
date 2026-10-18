package libcore

import (
	"archive/zip"
	"errors"
	"fmt"
	"github.com/ulikunitz/xz"
	"io"
	"os"
	"path/filepath"
	"strings"
)

func Unxz(archive string, path string) (err error) {
	input, err := os.Open(archive)
	if err != nil {
		return err
	}
	defer input.Close()
	reader, err := xz.NewReader(input)
	if err != nil {
		return err
	}
	output, err := os.Create(path)
	if err != nil {
		return err
	}
	defer func() { err = errors.Join(err, output.Close()) }()
	_, err = io.Copy(output, reader)
	return
}

const maxZipBytes uint64 = 1024 * 1024 * 1024
const maxZipEntries = 100000

func safeZipName(name string) (string, error) {
	// ZIP uses '/' separators. Reject NTFS streams, drives, backslashes and
	// traversal on every host, including cross-platform tests.
	if strings.ContainsAny(name, "\\:\x00") {
		return "", fmt.Errorf("unsafe ZIP path %q", name)
	}
	rel := filepath.FromSlash(strings.TrimSuffix(name, "/"))
	if !filepath.IsLocal(rel) || rel == "." {
		return "", fmt.Errorf("unsafe ZIP path %q", name)
	}
	for _, part := range strings.Split(name, "/") {
		if part == ".." {
			return "", fmt.Errorf("unsafe ZIP path %q", name)
		}
	}
	return rel, nil
}

func Unzip(archive string, path string) error {
	reader, err := zip.OpenReader(archive)
	if err != nil {
		return err
	}
	defer reader.Close()
	if len(reader.File) > maxZipEntries {
		return fmt.Errorf("ZIP exceeds entry limit")
	}
	var total uint64
	names := make([]string, len(reader.File))
	for index, file := range reader.File {
		name, err := safeZipName(file.Name)
		if err != nil {
			return err
		}
		names[index] = name
		if !file.Mode().IsRegular() && !file.Mode().IsDir() {
			return fmt.Errorf("ZIP entry is not a regular file or directory: %q", file.Name)
		}
		if file.UncompressedSize64 > maxZipBytes || total > maxZipBytes-file.UncompressedSize64 {
			return fmt.Errorf("ZIP exceeds 1 GiB expansion limit")
		}
		total += file.UncompressedSize64
	}
	if err = os.MkdirAll(path, 0755); err != nil {
		return err
	}
	// os.Root enforces containment during filesystem operations, including
	// existing symlinks and path swaps; a lexical prefix check alone cannot.
	root, err := os.OpenRoot(path)
	if err != nil {
		return err
	}
	defer root.Close()
	for index, file := range reader.File {
		name := names[index]
		if file.FileInfo().IsDir() {
			if err = root.MkdirAll(name, 0755); err != nil {
				return err
			}
			continue
		}
		if err = root.MkdirAll(filepath.Dir(name), 0755); err != nil {
			return err
		}
		input, err := file.Open()
		if err != nil {
			return err
		}
		output, err := root.OpenFile(name, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0644)
		if err != nil {
			input.Close()
			return err
		}
		_, copyErr := io.Copy(output, io.LimitReader(input, int64(file.UncompressedSize64)+1))
		err = errors.Join(copyErr, input.Close(), output.Close())
		if err != nil {
			return err
		}
	}
	return nil
}
