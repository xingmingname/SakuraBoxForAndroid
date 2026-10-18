package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
)

func main() {
	// Go's package loader searches the driver environment for PATH. Supply a
	// deterministic Windows spelling for tools inherited through gomobile.
	goBin:=filepath.Join(os.Getenv("GOROOT"),"bin")
	path:=goBin+string(os.PathListSeparator)+os.Getenv("PATH")
	if err:=os.Setenv("PATH",path);err!=nil{fmt.Fprintln(os.Stderr,err);os.Exit(1)}
	executable,err:=os.Executable();if err!=nil{fmt.Fprintln(os.Stderr,err);os.Exit(1)}
	command:=exec.Command(filepath.Join(filepath.Dir(executable),"gobind-matsuri.exe"),os.Args[1:]...)
	command.Stdin=os.Stdin;command.Stdout=os.Stdout;command.Stderr=os.Stderr
	if err=command.Run();err!=nil{fmt.Fprintln(os.Stderr,err);os.Exit(1)}
}
