{{/* Kaynak adlari tek yerden uretilir: "kervan-<servis>" */}}
{{- define "kervan.fullname" -}}
kervan-{{ . }}
{{- end -}}

{{- define "kervan.labels" -}}
app.kubernetes.io/name: {{ .name }}
app.kubernetes.io/instance: {{ .release }}
app.kubernetes.io/part-of: kervan
app.kubernetes.io/managed-by: Helm
{{- end -}}
