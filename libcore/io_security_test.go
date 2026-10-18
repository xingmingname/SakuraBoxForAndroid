package libcore

import (
	"archive/zip"
	"os"
	"path/filepath"
	"testing"
)

func testZIP(t *testing.T, name string, mode os.FileMode) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "test.zip")
	file, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	writer := zip.NewWriter(file)
	header := &zip.FileHeader{Name: name}
	header.SetMode(mode)
	entry, err := writer.CreateHeader(header)
	if err != nil {
		t.Fatal(err)
	}
	_, _ = entry.Write([]byte("test-data"))
	if err = writer.Close(); err != nil {
		t.Fatal(err)
	}
	file.Close()
	return path
}
func TestZIPContainmentAndNormalExtraction(t *testing.T) {
	for _, name := range []string{"../escape.txt", "sub/../../escape.txt", "/absolute.txt", "C:/escape.txt", "..\\escape.txt", "file.txt:stream"} {
		root := filepath.Join(t.TempDir(), "output")
		if err := Unzip(testZIP(t, name, 0644), root); err == nil {
			t.Fatalf("unsafe path accepted: %s", name)
		}
		if _, err := os.Stat(root); !os.IsNotExist(err) {
			t.Fatal("invalid ZIP wrote output before validation")
		}
	}
	root := t.TempDir()
	if err := Unzip(testZIP(t, "sub/nested/file.txt", 0644), root); err != nil {
		t.Fatal(err)
	}
	content, err := os.ReadFile(filepath.Join(root, "sub/nested/file.txt"))
	if err != nil || string(content) != "test-data" {
		t.Fatal("normal ZIP without directory entries failed")
	}
	if err := Unzip(testZIP(t, "link", os.ModeSymlink|0777), root); err == nil {
		t.Fatal("archive symlink accepted")
	}
}
func TestZIPEscapingExistingSymlink(t *testing.T) {
	root := t.TempDir()
	outside := t.TempDir()
	if err := os.Symlink(outside, filepath.Join(root, "link")); err != nil {
		t.Skipf("host cannot create a symlink: %v", err)
	}
	err := Unzip(testZIP(t, "link/outside.txt", 0644), root)
	if err == nil {
		t.Fatal("existing escaping symlink followed")
	}
	entries, _ := os.ReadDir(outside)
	if len(entries) != 0 {
		t.Fatal("outside directory modified")
	}
}
func TestZIPPlatformNeutralNames(t *testing.T) {
	for _, name := range []string{"..", "a/../b", "\\host\\share", "a\x00b"} {
		if _, err := safeZipName(name); err == nil {
			t.Fatalf("unsafe name accepted %q", name)
		}
	}
}
